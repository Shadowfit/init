#!/bin/bash
# 리플리카 승격 시간 — 소스가 죽은 순간부터 리플리카가 새 쓰기를 받기까지 (RPO·RTO 분기점 R1 후속)
#
# 질문: 리플리카(구성 3)를 붙이면 호스트 상실 RTO 가 «구성 2 의 약 7분» 보다 실제로 짧아지나?
#       문서는 «리플리카를 붙이면 RTO 가 짧아진다» 고 가정했지 승격 시간을 잰 적이 없다.
#
# 재는 구간 (소스를 죽인 시각 t_kill 기준, 스크립트로 잴 수 있는 것만):
#   io_stop_ms   STOP REPLICA IO_THREAD
#   apply_ms     받아 둔 릴레이 로그를 끝까지 적용 (WAIT_FOR_EXECUTED_GTID_SET) ← 밀린 양에 비례할 것으로 예상
#   switch_ms    STOP REPLICA · RESET REPLICA ALL · read_only 해제
#   write_ms     첫 쓰기 성공 + 읽기 확인
#   promote_ms   위 넷의 합 = 승격 시간
#
# 🔴 제외한 것(사람/앱 시간): 소스가 죽은 것을 알아채는 시간 · 승격을 결정하는 시간 · 앱 연결을 새 서버로 바꾸는 시간.
#    실제 RTO 는 promote_ms 보다 이만큼 크다. 이 값은 «스크립트로 자동화했을 때의 하한» 이다.
# 🔴 비동기 복제(반동기 아님). 소스가 죽을 때 리플리카가 아직 못 받은 트랜잭션은 사라진다 → lost_tx 로 같이 남긴다.
# 🔴 소스·리플리카가 같은 박스의 컨테이너다. 부하 중엔 CPU 를 나눠 쓰고(밀림이 두 박스일 때와 다를 수 있다),
#    소스가 죽은 뒤엔 리플리카가 CPU 를 다 쓴다. 두 박스 구성의 승격 시간은 따로 안 쟀다.
#
# 팔 (밀림 조건) — 쓰기 속도:
#   paced    문서 §8-1 가정 피크 45행/초 — 밀림이 거의 없을 것으로 예상
#   stress   연결 여럿이 쉬지 않고 쓴다 — 밀림이 쌓인다. 상한 확인용. 운영 값이 아니다
# 밀림은 조작하는 변수가 아니라 «죽이는 순간 관측한 값» 이다 — backlog_tx 를 같이 남겨 승격 시간을 그것으로 본다.

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
WORK="${WORK:-$HERE/_work}"
mkdir -p "$OUT" "$WORK"

IMG=${MYSQL_IMAGE:-mysql:8.0}
PW=repl
SRC=pr_src; REP=pr_rep; NET=pr_net
SRC_VOL=pr_src_data; REP_VOL=pr_rep_data
# 시행 순서 — 앞의 둘은 버림판(팔마다 하나). 이후는 ABBA 로 팔과 순서가 섞이지 않게
TRIALS=${TRIALS:-"paced stress stress paced paced stress stress paced paced stress"}
PACED_ROWS_PER_S=${PACED_ROWS_PER_S:-45}
ROWS_PER_TX=${ROWS_PER_TX:-25}
STRESS_CONNS=${STRESS_CONNS:-4}
PACED_SECS=${PACED_SECS:-120}
STRESS_SECS=${STRESS_SECS:-300}
SERVER_OPTS=(--log-bin=binlog --binlog-format=ROW --gtid-mode=ON --enforce-gtid-consistency=ON
             --innodb-flush-log-at-trx-commit=1 --sync-binlog=1
             --innodb-buffer-pool-size=${BUFFER_POOL:-1G} --innodb-redo-log-capacity=${REDO_CAPACITY:-2G})

log() { printf '[promote] %s\n' "$*" >&2; }
die() { log "🔴 $*"; exit 1; }
q()  { docker exec -i "$1" mysql -uroot -p"$PW" -N -s -e "$2" 2>/dev/null; }
now_ms() { date +%s%3N; }
source "$HERE/../binlog-replay-time-2026-11-07/_writer_common.sh"

wait_ready() {
  local i
  for i in $(seq 1 300); do [ "$(q "$1" "SELECT @@skip_networking;")" = 0 ] && return 0; sleep 1; done
  die "$1 가 5분 안에 쿼리를 안 받는다"
}

# GTID 집합 문자열 → 트랜잭션 수. "uuid:1-5:8-9" → 5 + 2 = 7. UUID 가 여럿이어도 합친다.
gtid_count() { echo "$1" | tr -d ' ' | tr ',' '\n' | awk -F: 'NF>=2{for(i=2;i<=NF;i++){n=split($i,a,"-"); s+=(n==2)?(a[2]-a[1]+1):1}} END{print s+0}'; }

make_paced_proc() {
  cat > "$WORK/_paced.sql" <<'SQL'
DELIMITER //
DROP PROCEDURE IF EXISTS lab.paced_ack //
CREATE PROCEDURE lab.paced_ack(IN p_session BIGINT, IN p_rows INT, IN p_secs INT, IN p_gap DOUBLE)
BEGIN
  DECLARE v_end DATETIME(6);
  DECLARE v_off INT DEFAULT 0;
  DECLARE v_ver BIGINT;
  SET v_end = SYSDATE(6) + INTERVAL p_secs SECOND;
  WHILE SYSDATE(6) < v_end DO
    START TRANSACTION;
      INSERT INTO lab.pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)
        SELECT p_session, ts, jc, 75.0, 1, fb FROM lab._tpl WHERE n >= v_off AND n < v_off + p_rows;
      UPDATE lab.session_probe SET version = version + 1, last_active_at = SYSDATE(6) WHERE id = p_session;
      SELECT version INTO v_ver FROM lab.session_probe WHERE id = p_session;
    COMMIT;
    SELECT p_session, v_ver, ROUND(UNIX_TIMESTAMP(SYSDATE(6)) * 1000000);
    SET v_off = (v_off + p_rows) % (750 - p_rows);
    DO SLEEP(p_gap);
  END WHILE;
END //
DELIMITER ;
SQL
  docker exec -i "$SRC" mysql -uroot -p"$PW" < "$WORK/_paced.sql" 2>/dev/null || die "paced 프로시저 생성 실패"
}

start_pair() {
  docker rm -f "$SRC" "$REP" >/dev/null 2>&1 || true
  docker network create "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$SRC_VOL" "$REP_VOL" >/dev/null 2>&1 || true
  docker volume create "$SRC_VOL" >/dev/null; docker volume create "$REP_VOL" >/dev/null
  docker run -d --name "$SRC" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$SRC_VOL:/var/lib/mysql" \
    "$IMG" --server-id=1 "${SERVER_OPTS[@]}" >/dev/null
  docker run -d --name "$REP" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$REP_VOL:/var/lib/mysql" \
    "$IMG" --server-id=2 "${SERVER_OPTS[@]}" >/dev/null
  wait_ready "$SRC"; wait_ready "$REP"
  # 읽기 전용은 기동 «뒤에» 건다 — 옵션으로 주면 이미지의 초기화(root 계정 설정)가 쓰기를 못 해 서버가 안 뜬다
  q "$REP" "SET GLOBAL read_only=1; SET GLOBAL super_read_only=1;" || die "읽기 전용 설정 실패"
  q "$REP" "CHANGE REPLICATION SOURCE TO SOURCE_HOST='$SRC', SOURCE_USER='root', SOURCE_PASSWORD='$PW',
            SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA;" || die "복제 연결 실패"
  q "$SRC" "
    CREATE DATABASE lab;
    CREATE TABLE lab.pose_data_scale (
      id bigint NOT NULL AUTO_INCREMENT, session_id bigint NOT NULL, timestamp_sec double NOT NULL,
      joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL, sync_rate double DEFAULT NULL,
      is_correct tinyint(1) DEFAULT 1, feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    CREATE TABLE lab.session_probe (id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
                                    last_active_at DATETIME(6) NULL) ENGINE=InnoDB;
    CREATE TABLE lab.promo_probe (id INT PRIMARY KEY AUTO_INCREMENT, note VARCHAR(40)) ENGINE=InnoDB;" || die "스키마 실패"
  local i vals=""
  for i in $(seq 1 "$STRESS_CONNS"); do vals="$vals${vals:+,}($((100000 + i)),0,NULL)"; done
  q "$SRC" "INSERT INTO lab.session_probe VALUES $vals;"
  setup_writer "$SRC"
  make_paced_proc
  # 시작 시점엔 리플리카가 따라잡은 상태여야 한다
  local g; g=$(q "$SRC" "SELECT @@gtid_executed;")
  [ "$(q "$REP" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$(echo "$g" | tr -d '\n')', 120);")" = 0 ] || die "리플리카가 초기 상태를 못 따라잡았다"
}

trial() {  # $1 번호 $2 팔 $3 kind(버림|본판)
  local idx=$1 arm=$2 kind=$3 tdir="$WORK/t$1" i pids=()
  mkdir -p "$tdir"
  log "── 판 $idx ($kind) 팔=$arm"
  start_pair
  local secs; [ "$arm" = paced ] && secs=$PACED_SECS || secs=$STRESS_SECS
  if [ "$arm" = paced ]; then
    local gap; gap=$(awk -v r="$ROWS_PER_TX" -v p="$PACED_ROWS_PER_S" 'BEGIN{printf "%.3f", r/p}')
    docker run --rm --network "$NET" "$IMG" mysql -h "$SRC" -uroot -p"$PW" --unbuffered -N -B \
      -e "CALL lab.paced_ack(100001, $ROWS_PER_TX, 36000, $gap);" > "$tdir/w_1.tsv" 2>/dev/null &
    pids+=($!)
  else
    for i in $(seq 1 "$STRESS_CONNS"); do
      docker run --rm --network "$NET" "$IMG" mysql -h "$SRC" -uroot -p"$PW" --unbuffered -N -B \
        -e "CALL lab.load_run($((100000 + i)), $ROWS_PER_TX, 36000);" > "$tdir/w_$i.tsv" 2>/dev/null &
      pids+=($!)
    done
  fi
  sleep "$secs"

  # 죽이기 직전의 상태 — 밀림은 «관측값» 이다
  local src_g rep_g lag
  src_g=$(q "$SRC" "SELECT @@gtid_executed;")
  rep_g=$(q "$REP" "SELECT @@gtid_executed;")
  lag=$(docker exec "$REP" mysql -uroot -p"$PW" -e "SHOW REPLICA STATUS\G" 2>/dev/null | awk '/Seconds_Behind_Source/{print $2}')
  # 리플리카가 아직 적용 못 한 트랜잭션 수 = (소스 집합 − 리플리카 집합). 죽이기 직전 값이라 몇 건 오차가 있다
  local backlog_tx behind
  behind=$(q "$REP" "SELECT GTID_SUBTRACT('$(echo "$src_g" | tr -d '\n ')', '$(echo "$rep_g" | tr -d '\n ')');" | tr -d '\n ')
  backlog_tx=$(gtid_count "$behind")
  # docker exec 한 번의 고정 비용 — 승격 구간마다 이만큼이 껴 있다 (mysql 클라이언트를 매번 새로 띄운다)
  local o0 o1 exec_ms
  o0=$(now_ms); q "$REP" "SELECT 1;" >/dev/null; o1=$(now_ms); exec_ms=$((o1 - o0))

  local t_kill; t_kill=$(now_ms)
  docker kill "$SRC" >/dev/null           # 호스트 상실의 대리 — 프로세스와 네트워크가 한꺼번에 사라진다
  for i in "${pids[@]}"; do wait "$i" 2>/dev/null || true; done

  # ── 승격 ─────────────────────────────────────────────────────────────
  local t0 t1 t2 t3 t4 retrieved w
  t0=$(now_ms)
  q "$REP" "STOP REPLICA IO_THREAD;" >/dev/null
  t1=$(now_ms)
  retrieved=$(q "$REP" "SELECT RECEIVED_TRANSACTION_SET FROM performance_schema.replication_connection_status;" | tr -d '\n ')
  if [ -n "$retrieved" ]; then
    w=$(q "$REP" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$retrieved', 1800);")
    [ "$w" = 0 ] || log "⚠️ 적용 대기가 상한을 넘겼거나 실패 (w=$w)"
  fi
  t2=$(now_ms)
  q "$REP" "STOP REPLICA; RESET REPLICA ALL; SET GLOBAL super_read_only=0; SET GLOBAL read_only=0;" >/dev/null || log "⚠️ 전환 실패"
  t3=$(now_ms)
  q "$REP" "INSERT INTO lab.promo_probe (note) VALUES ('first-write'); SELECT COUNT(*) FROM lab.promo_probe;" >/dev/null || log "⚠️ 첫 쓰기 실패"
  t4=$(now_ms)

  # ── 잃은 양 — 클라이언트가 «커밋 성공» 으로 받은 마지막 버전 합 vs 승격된 리플리카의 합 ──
  local sum_acked sum_rest lost
  sum_acked=$(for f in "$tdir"/w_*.tsv; do awk 'NF>=3{v=$2} END{print v+0}' "$f"; done | awk '{s+=$1} END{print s+0}')
  sum_rest=$(q "$REP" "SELECT COALESCE(SUM(version),0) FROM lab.session_probe;")
  lost=$((sum_acked - sum_rest))

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$idx" "$kind" "$arm" "${lag:-NULL}" "$backlog_tx" "$((t1 - t0))" "$((t2 - t1))" "$((t3 - t2))" "$((t4 - t3))" \
    "$((t4 - t0))" "$sum_acked" "$lost" "$exec_ms" >> "$OUT/trials.tsv"
  log "판 $idx: 밀림 ${lag:-?}s·${backlog_tx}tx → 승격 $((t4 - t0))ms (적용 $((t2 - t1))) · 잃은 트랜잭션 $lost"
  docker rm -f "$SRC" "$REP" >/dev/null 2>&1 || true
}

{
  printf 'trial\tkind\tarm\tlag_s_at_kill\tbacklog_tx\tio_stop_ms\tapply_ms\tswitch_ms\twrite_ms\tpromote_ms\tacked\tlost_tx\texec_ms\n'
} > "$OUT/trials.tsv"
{
  echo "# 승격 실험 조건 ($(date -Is))"
  echo "mysql image      : $IMG"
  echo "server opts      : ${SERVER_OPTS[*]}"
  echo "replica workers  : (판마다 시작 후 기록하지 않음 — 기본값, 8.0.46 은 replica_parallel_workers 기본 4)"
  echo "trials           : $TRIALS (앞 paced·stress 각 첫 판은 버림)"
  echo "paced            : ${PACED_ROWS_PER_S} 행/초(문서 §8-1 가정) ${PACED_SECS}s · stress: ${STRESS_CONNS} 연결 ${STRESS_SECS}s"
  echo "제외             : 감지·판단·앱 연결 전환 시간"
} > "$OUT/CONDITIONS.txt"

seen_paced=0; seen_stress=0; n=0
for arm in $TRIALS; do
  n=$((n + 1)); kind=본판
  if [ "$arm" = paced ] && [ "$seen_paced" = 0 ]; then kind=버림; seen_paced=1; fi
  if [ "$arm" = stress ] && [ "$seen_stress" = 0 ]; then kind=버림; seen_stress=1; fi
  trial "$n" "$arm" "$kind"
done
log "완료 → $OUT/trials.tsv"
