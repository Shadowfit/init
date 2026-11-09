#!/bin/bash
# 반출 업로드 비용 — binlog 를 N초마다 닫아 S3 로 올릴 때 «한 번에 얼마가 드나» (RPO·RTO 분기점 §6-2 후속)
#
# 호스트 상실 rig(hostloss_rpo.sh)은 «잃은 시간» 만 쟀고 업로드 자체의 비용(파일 크기·FLUSH 시간·S3 업로드 시간)은
# 안 쟀다. 그 README 가 «주기 + 업로드 시간으로 잡아야 한다» 고 적었는데 그 업로드 시간이 이 rig 의 출력이다.
#
# 재는 것 (반출 1건마다 한 줄):
#   flush_ms   FLUSH BINARY LOGS 가 끝나기까지
#   bytes      닫힌 파일 크기
#   put_ms     그 파일을 S3 로 올리는 데 걸린 시간 (EC2 에선 진짜 S3, 로컬 스모크에선 가짜 — 스모크 값은 의미 없음)
#
# 팔 (쓰기 속도 × 반출 주기), 각 팔 안에서 반출이 여러 번 일어나므로 «반출 1건» 이 반복 단위다:
#   rate=paced   문서 §8-1 가정 피크(45행/초)로 쓴다 — 운영에 가까운 값
#   rate=stress  연결 여럿이 쉬지 않고 쓴다 — 상한 확인용. 운영 값이 아니다
#   interval     60, 300 초. 순서는 ABBA (60 300 300 60) 라 «팔» 과 «판 순서» 가 섞이지 않는다
#
# 🔴 쓰기 속도 가정(45행/초)은 측정이 아니라 문서 가정이다 — 결과에 «가정 피크 기준» 이라고 적을 것.
# 🔴 같은 리전 S3 · 단일 기종 · 한 번의 라운드. 다른 리전으로 반출하면 업로드 시간이 달라진다.

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
WORK="${WORK:-$HERE/_work}"
mkdir -p "$OUT" "$WORK"

IMG=${MYSQL_IMAGE:-mysql:8.0}
PW=repl
SRC=sc_src
SRC_VOL=sc_src_data
S3_BASE=${S3_BASE:-}               # s3:// 면 진짜 S3, 아니면 가짜(로컬 스모크)
INTERVALS=${INTERVALS:-"60 300 300 60"}          # ABBA — 이 순서 그대로 돈다
RATES=${RATES:-"paced stress"}
PACED_ROWS_PER_S=${PACED_ROWS_PER_S:-45}         # 문서 §8-1 피크 가정
ROWS_PER_TX=${ROWS_PER_TX:-25}
STRESS_CONNS=${STRESS_CONNS:-4}
# 세그먼트 길이(초): 반출이 최소 이 정도 일어나게 — 60초 주기는 짧게, 300초 주기는 길게
SEG_SECS_60=${SEG_SECS_60:-600}
SEG_SECS_300=${SEG_SECS_300:-1200}
STRESS_SECS_60=${STRESS_SECS_60:-300}
STRESS_SECS_300=${STRESS_SECS_300:-600}
SERVER_OPTS=(--log-bin=binlog --binlog-format=ROW --gtid-mode=OFF --enforce-gtid-consistency=OFF
             --innodb-flush-log-at-trx-commit=1 --sync-binlog=1
             --innodb-buffer-pool-size=${BUFFER_POOL:-1G} --innodb-redo-log-capacity=${REDO_CAPACITY:-2G}
             --max-binlog-size=1G)

log() { printf '[shipcost] %s\n' "$*" >&2; }
die() { log "🔴 $*"; exit 1; }
q()  { docker exec -i "$1" mysql -uroot -p"$PW" -N -s -e "$2" 2>/dev/null; }
now_ms() { date +%s%3N; }
source "$HERE/../binlog-replay-time-2026-11-07/_writer_common.sh"

wait_ready() {
  local i
  for i in $(seq 1 300); do [ "$(q "$1" "SELECT @@skip_networking;")" = 0 ] && return 0; sleep 1; done
  die "$1 가 5분 안에 쿼리를 안 받는다"
}

mountpoint_of() { docker volume inspect -f '{{.Mountpoint}}' "$1" 2>/dev/null; }

# 닫힌 binlog 한 파일을 올린다 → 크기와 업로드 ms 를 출력. 마운트포인트에 닿으면 그대로(EC2),
# 안 닿으면(Docker Desktop) docker cp 로 꺼낸 뒤 올린다 — 그 경우 put_ms 는 cp 시간을 빼고 업로드만 잰다.
ship_one() {  # $1 파일명 $2 키 → stdout "bytes put_ms"
  local f=$1 key=$2 mp path bytes t0 t1
  mp=$(mountpoint_of "$SRC_VOL")
  if [ -n "$mp" ] && [ -r "$mp/$f" ]; then path="$mp/$f"
  else path="$WORK/_cp_$f"; docker cp "$SRC:/var/lib/mysql/$f" "$path" >/dev/null; fi
  bytes=$(stat -c%s "$path")
  t0=$(now_ms)
  if [[ "$S3_BASE" == s3://* ]]; then aws s3 cp "$path" "$S3_BASE/$key" --only-show-errors
  else mkdir -p "$WORK/_fake_s3/$(dirname "$key")" && cp "$path" "$WORK/_fake_s3/$key"; fi
  t1=$(now_ms)
  [[ "$path" == "$WORK"/_cp_* ]] && rm -f "$path"
  echo "$bytes $((t1 - t0))"
}

# 반출기 — iv 초마다 FLUSH → 새로 닫힌 파일을 올린다. 한 줄 = 반출 1건.
shipper() {  # $1 seg $2 rate $3 interval $4 시작 파일 $5 기록 파일
  local seg=$1 rate=$2 iv=$3 from=$4 rec=$5 k=0 f
  local -A shipped=()   # 세그먼트마다 binlog 이름이 처음부터 다시 시작하므로 파일이 아니라 이 상태로 중복을 거른다
  while docker inspect "$SRC" >/dev/null 2>&1; do
    sleep "$iv"
    local t0 t1 fl closed
    t0=$(now_ms); q "$SRC" "FLUSH BINARY LOGS;" >/dev/null || break; t1=$(now_ms); fl=$((t1 - t0))
    closed=$(q "$SRC" "SHOW BINARY LOGS;" | awk '{print $1}' | sed '$d') || break
    for f in $closed; do
      [[ "$f" < "$from" ]] && continue
      [ -n "${shipped[$f]:-}" ] && continue
      shipped[$f]=1
      k=$((k + 1))
      read -r bytes put <<<"$(ship_one "$f" "shipcost/$seg/$f")" || break 2
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$seg" "$rate" "$iv" "$k" "$f" "$bytes" "$fl" "$put" >> "$rec"
      fl=0   # FLUSH 는 주기마다 한 번 — 한 주기에 파일이 둘 이상 닫혀도 FLUSH 시간은 첫 줄에만
    done
  done
}

# paced writer — 한 트랜잭션(ROWS_PER_TX 행 + 세션 행 UPDATE) 뒤 gap 만큼 쉰다
make_paced_proc() {
  cat > "$WORK/_paced.sql" <<'SQL'
DELIMITER //
DROP PROCEDURE IF EXISTS lab.paced_run //
CREATE PROCEDURE lab.paced_run(IN p_session BIGINT, IN p_rows INT, IN p_secs INT, IN p_gap DOUBLE)
BEGIN
  DECLARE v_end DATETIME(6);
  DECLARE v_off INT DEFAULT 0;
  SET v_end = SYSDATE(6) + INTERVAL p_secs SECOND;
  WHILE SYSDATE(6) < v_end DO
    START TRANSACTION;
      INSERT INTO lab.pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)
        SELECT p_session, ts, jc, 75.0, 1, fb FROM lab._tpl WHERE n >= v_off AND n < v_off + p_rows;
      UPDATE lab.session_probe SET version = version + 1, last_active_at = SYSDATE(6) WHERE id = p_session;
    COMMIT;
    SET v_off = (v_off + p_rows) % (750 - p_rows);
    DO SLEEP(p_gap);
  END WHILE;
END //
DELIMITER ;
SQL
  docker exec -i "$SRC" mysql -uroot -p"$PW" < "$WORK/_paced.sql" 2>/dev/null || die "paced 프로시저 생성 실패"
}

setup() {
  docker rm -f "$SRC" >/dev/null 2>&1 || true
  docker volume rm -f "$SRC_VOL" >/dev/null 2>&1 || true; docker volume create "$SRC_VOL" >/dev/null
  docker network create sc_net >/dev/null 2>&1 || true
  docker run -d --name "$SRC" --network sc_net -e MYSQL_ROOT_PASSWORD="$PW" -v "$SRC_VOL:/var/lib/mysql" \
    "$IMG" --server-id=1 "${SERVER_OPTS[@]}" >/dev/null
  wait_ready "$SRC"
  q "$SRC" "
    CREATE DATABASE lab;
    CREATE TABLE lab.pose_data_scale (
      id bigint NOT NULL AUTO_INCREMENT, session_id bigint NOT NULL, timestamp_sec double NOT NULL,
      joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL, sync_rate double DEFAULT NULL,
      is_correct tinyint(1) DEFAULT 1, feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    CREATE TABLE lab.session_probe (id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
                                    last_active_at DATETIME(6) NULL) ENGINE=InnoDB;" || die "스키마 생성 실패"
  local i vals=""
  for i in $(seq 1 "$STRESS_CONNS"); do vals="$vals${vals:+,}($((100000 + i)),0,NULL)"; done
  q "$SRC" "INSERT INTO lab.session_probe VALUES $vals;"
  setup_writer "$SRC"
  make_paced_proc
}

segment() {  # $1 seg번호 $2 rate $3 interval
  local seg=$1 rate=$2 iv=$3 secs i pids=() from
  if [ "$rate" = paced ]; then [ "$iv" = 60 ] && secs=$SEG_SECS_60 || secs=$SEG_SECS_300
  else [ "$iv" = 60 ] && secs=$STRESS_SECS_60 || secs=$STRESS_SECS_300; fi
  log "── 세그먼트 $seg rate=$rate interval=${iv}s 길이=${secs}s"
  # 세그먼트마다 같은 출발 상태: 표를 비우고 binlog 를 처음으로
  q "$SRC" "TRUNCATE TABLE lab.pose_data_scale; RESET MASTER;" >/dev/null
  from=$(q "$SRC" "SHOW MASTER STATUS;" | awk '{print $1}')
  local rec="$OUT/ship_cost.tsv"
  local c0; c0=$(q "$SRC" "SELECT SUM(version) FROM lab.session_probe;")

  if [ "$rate" = paced ]; then
    local gap; gap=$(awk -v r="$ROWS_PER_TX" -v p="$PACED_ROWS_PER_S" 'BEGIN{printf "%.3f", r/p}')
    docker run --rm --network sc_net "$IMG" mysql -h "$SRC" -uroot -p"$PW" -N -B \
      -e "CALL lab.paced_run(100001, $ROWS_PER_TX, $secs, $gap);" >/dev/null 2>&1 &
    pids+=($!)
  else
    for i in $(seq 1 "$STRESS_CONNS"); do
      docker run --rm --network sc_net "$IMG" mysql -h "$SRC" -uroot -p"$PW" --unbuffered -N -B \
        -e "CALL lab.load_run($((100000 + i)), $ROWS_PER_TX, $secs);" >/dev/null 2>&1 &
      pids+=($!)
    done
  fi
  sleep 3
  shipper "$seg" "$rate" "$iv" "$from" "$rec" &
  local ship_pid=$!
  for i in "${pids[@]}"; do wait "$i" 2>/dev/null || true; done
  kill "$ship_pid" 2>/dev/null || true; wait "$ship_pid" 2>/dev/null || true

  local c1 rows bytes_total
  c1=$(q "$SRC" "SELECT SUM(version) FROM lab.session_probe;")
  rows=$(q "$SRC" "SELECT COUNT(*) FROM lab.pose_data_scale;")
  bytes_total=$(q "$SRC" "SHOW BINARY LOGS;" | awk '{s+=$2} END{print s+0}')
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$seg" "$rate" "$iv" "$secs" "$((c1 - c0))" "$rows" "$bytes_total" >> "$OUT/segments.tsv"
  log "세그먼트 $seg 끝 — 트랜잭션 $((c1 - c0)) · 행 $rows · binlog $((bytes_total / 1024 / 1024))MB"
}

printf 'seg\trate\tinterval_s\tk\tfile\tbytes\tflush_ms\tput_ms\n' > "$OUT/ship_cost.tsv"
printf 'seg\trate\tinterval_s\tsecs\ttx\trows\tbinlog_bytes\n' > "$OUT/segments.tsv"
setup
seg=0
for rate in $RATES; do
  for iv in $INTERVALS; do
    seg=$((seg + 1))
    segment "$seg" "$rate" "$iv"
  done
done
docker rm -f "$SRC" >/dev/null 2>&1 || true
log "완료 → $OUT/ship_cost.tsv"
