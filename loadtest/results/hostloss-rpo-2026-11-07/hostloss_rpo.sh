#!/usr/bin/env bash
# 호스트 상실 — S3 에 올린 것만으로 되살리면 얼마를 잃고(RPO) 얼마 걸리나(RTO)
#
# 설계 문서: docs/decisions/rpo-rto-targets.md §5 구성 2 · §6-2
#   구성 2 = 백업 + binlog 를 주기적으로 서버 밖(S3)에. 그 RPO 는 «binlog 반출 주기» 로 정해진다고 적었는데
#   추정이었다. 실제로 쓰는 도중 호스트를 통째로 잃고, S3 만으로 되살려 잃은 양을 센다.
#
# 판 하나의 흐름
#   ① 소스를 기준 사본에서 띄우고 writer 시작 (실제 JSON 페이로드 — ../binlog-replay-time-2026-11-07/_writer_common.sh)
#   ② XtraBackup 백업 → S3 (쓰기가 도는 중에)
#   ③ 반출기: INTERVAL 초마다 FLUSH BINARY LOGS → 닫힌 binlog 를 S3 로
#   ④ 상실: 백업 뒤 2×INTERVAL + 판마다 다른 오프셋(주기 안의 0~1)에 소스 컨테이너와 볼륨을 통째로 지운다
#   ⑤ 복구 (새 서버): S3 에서 백업 받기 → prepare·copy-back → 기동 → S3 에서 binlog 받기 → 백업 좌표부터 재생
#   ⑥ 잃은 양: writer 가 클라이언트 쪽에 남긴 «커밋 성공» 기록 ↔ 복구된 세션 version
#
# 팔 — INTERVALS (기본 60 300). 판 — 버림 1 + 본판 REPS(기본 3) × 팔.
# 정본 «커밋 성공» = writer 클라이언트가 받은 (세션, version, 커밋 시각) 줄. 서버가 사라져도 남는다.
# RPO_s = 상실 시각 − 복구된 마지막 커밋의 시각(세션들 중 가장 늦은 것).
#
# S3 — S3_BASE 가 s3:// 면 진짜 S3(EC2), 아니면 로컬 «가짜 S3» 볼륨(스모크용 — 시간 값은 의미 없음).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
WORK="${WORK:-$HERE/_work}"
mkdir -p "$OUT" "$WORK"

IMG=${MYSQL_IMAGE:-mysql:8.0}
XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}
PW=repl
SRC=hl_src; DST=hl_dst; NET=hl_net
SRC_VOL=hl_src_data; BASE_VOL=hl_base_data; DST_VOL=hl_dst_data; BK_VOL=hl_backup; DL_VOL=hl_download; FAKE_S3=hl_fake_s3
S3_BASE=${S3_BASE:-}          # 비면 가짜 S3
BASE_ROWS=${BASE_ROWS:-10000000}
INTERVALS=${INTERVALS:-"60 300"}
REPS=${REPS:-3}
CONNS=${CONNS:-15}
ROWS_PER_TX=${ROWS_PER_TX:-25}
SERVER_OPTS=(--log-bin=binlog --binlog-format=ROW --gtid-mode=OFF --enforce-gtid-consistency=OFF
             --innodb-flush-log-at-trx-commit=1 --sync-binlog=1
             --innodb-buffer-pool-size=${BUFFER_POOL:-1G}
             # 🔴 redo 용량을 늘린다 — 기본(100MB)이면 쓰기가 도는 중 XtraBackup 이 redo 를 다 복사하기 전에
             #    덮어써져 «could not find redo log file with LSN» 으로 백업이 실패한다(로컬 스모크가 실제로 그랬다).
             #    운영에서도 같은 조건이면 같은 일이 난다 — 백업 창의 쓰기량보다 redo 가 커야 한다
             --innodb-redo-log-capacity=${REDO_CAPACITY:-2G})

log() { printf '[hostloss] %s\n' "$*" >&2; }
die() { log "🔴 $*"; exit 1; }
q()  { docker exec -i "$1" mysql -uroot -p"$PW" -N -s -e "$2" 2>/dev/null; }
now_ms() { date +%s%3N; }
helper() { MSYS_NO_PATHCONV=1 docker run --rm --user 0 "$@"; }
source "$HERE/../binlog-replay-time-2026-11-07/_writer_common.sh"

wait_ready() {
  local c=$1 i
  # 🔴 SELECT 1 로 기다리면 안 된다 — 엔트리포인트의 초기화용 임시 서버(--skip-networking)도 소켓으로는 받아서
  #    «떴다» 고 오판한다. 임시 서버는 skip_networking=1 이므로 0 이 될 때까지 기다린다.
  for i in $(seq 1 300); do [ "$(q "$c" "SELECT @@skip_networking;")" = 0 ] && return 0; sleep 1; done
  die "$c 가 5분 안에 쿼리를 안 받는다"
}

vol_reset() { docker volume rm -f "$1" >/dev/null 2>&1 || true; docker volume create "$1" >/dev/null; }

# ── S3 추상화 — 볼륨 안 경로 ↔ 키 ────────────────────────────────────────
mountpoint() { docker volume inspect -f '{{.Mountpoint}}' "$1"; }
s3_put() {  # $1 볼륨 $2 볼륨 안 경로 $3 키
  if [[ "$S3_BASE" == s3://* ]]; then aws s3 cp "$(mountpoint "$1")/$2" "$S3_BASE/$3" --only-show-errors
  else helper -v "$1:/v:ro" -v "$FAKE_S3:/s3" "$XB_IMAGE" bash -c "mkdir -p \"\$(dirname /s3/$3)\" && cp /v/$2 /s3/$3"; fi
}
s3_get() {  # $1 키 $2 볼륨 $3 볼륨 안 경로
  if [[ "$S3_BASE" == s3://* ]]; then aws s3 cp "$S3_BASE/$1" "$(mountpoint "$2")/$3" --only-show-errors
  else helper -v "$FAKE_S3:/s3:ro" -v "$2:/v" "$XB_IMAGE" bash -c "mkdir -p \"\$(dirname /v/$3)\" && cp /s3/$1 /v/$3"; fi
}
s3_ls() {  # $1 접두 → 키 이름들
  if [[ "$S3_BASE" == s3://* ]]; then aws s3 ls "$S3_BASE/$1/" | awk '{print $4}'
  else helper -v "$FAKE_S3:/s3:ro" "$XB_IMAGE" bash -c "ls /s3/$1/ 2>/dev/null" || true; fi
}

# ─────────────────────────────────────────────────────────────────────────
make_base() {
  log "기준 무대 — $BASE_ROWS 행"
  docker rm -f "$SRC" "$DST" >/dev/null 2>&1 || true
  docker network create "$NET" >/dev/null 2>&1 || true
  vol_reset "$SRC_VOL"
  [[ "$S3_BASE" == s3://* ]] || vol_reset "$FAKE_S3"
  docker run -d --name "$SRC" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$SRC_VOL:/var/lib/mysql" \
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
    CREATE TABLE lab._n (n INT PRIMARY KEY);
    INSERT INTO lab._n
      WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
      SELECT a.n + b.n*10 + c.n*100 + e.n*1000 FROM d a, d b, d c, d e;
    CREATE TABLE lab.session_probe (id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
                                    last_active_at DATETIME(6) NULL) ENGINE=InnoDB;" || die "스키마 생성 실패"
  local i vals=""
  for i in $(seq 1 "$CONNS"); do vals="$vals${vals:+,}($((100000 + i)),0,NULL)"; done
  q "$SRC" "INSERT INTO lab.session_probe VALUES $vals;"
  q "$SRC" "SET GLOBAL innodb_flush_log_at_trx_commit=2; SET GLOBAL sync_binlog=0;"
  local d=0
  while [ "$d" -lt "$BASE_ROWS" ]; do
    q "$SRC" "INSERT INTO lab.pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)
              SELECT 1 + FLOOR(n / 750), (n % 750) * 1.2, '{}', 75.0, 1, 'ok' FROM lab._n;"
    d=$((d + 10000))
  done
  q "$SRC" "SET GLOBAL innodb_flush_log_at_trx_commit=1; SET GLOBAL sync_binlog=1;"
  setup_writer "$SRC"
  # 시딩 binlog 를 기준 사본에 남기지 않는다 — 남기면 반출기가 백업과 무관한 옛 파일부터 올리느라 정작 백업 이후
  # 파일을 상실 전까지 못 올린다(로컬 스모크가 실제로 그랬다: 백업 좌표 000006, 올린 것 000001~000002)
  q "$SRC" "RESET MASTER;"
  docker stop "$SRC" >/dev/null
  vol_reset "$BASE_VOL"
  helper -v "$SRC_VOL:/from:ro" -v "$BASE_VOL:/to" "$XB_IMAGE" bash -c 'cp -a --reflink=never /from/. /to/ && rm -f /to/auto.cnf'
  docker rm -f "$SRC" >/dev/null
  log "기준 사본 완료"
}

start_src() {
  vol_reset "$SRC_VOL"
  helper -v "$BASE_VOL:/from:ro" -v "$SRC_VOL:/to" "$XB_IMAGE" bash -c 'cp -a --reflink=never /from/. /to/'
  docker run -d --name "$SRC" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$SRC_VOL:/var/lib/mysql" \
    "$IMG" --server-id=1 "${SERVER_OPTS[@]}" >/dev/null
  wait_ready "$SRC"
  q "$SRC" "FLUSH BINARY LOGS;"
  SHIP_FROM=$(q "$SRC" "SHOW MASTER STATUS;" | awk '{print $1}')   # 반출은 이 파일부터 — 판 시작 전 파일은 안 올린다
}

# 반출기 — INTERVAL 마다 binlog 를 닫고, 닫힌 파일 중 아직 안 올린 것을 올린다. 소스가 사라지면 스스로 끝난다.
shipper() {  # $1 interval $2 접두 $3 기록 파일
  local iv=$1 pre=$2 rec=$3 f
  : > "$rec"
  while docker inspect "$SRC" >/dev/null 2>&1; do
    sleep "$iv"
    q "$SRC" "FLUSH BINARY LOGS;" >/dev/null || break
    local closed; closed=$(q "$SRC" "SHOW BINARY LOGS;" | awk '{print $1}' | sed '$d') || break
    for f in $closed; do
      [[ "$f" < "$SHIP_FROM" ]] && continue
      grep -qx "$f" "$rec" 2>/dev/null && continue
      s3_put "$SRC_VOL" "$f" "$pre/binlog/$f" 2>/dev/null || break 2
      echo "$f" >> "$rec"
      printf '%s\t%s\n' "$(now_ms)" "$f" >> "$rec.times"
    done
  done
}

trial() {
  local idx=$1 iv=$2 kind=$3
  local pre="hostloss/t$idx"
  local tdir="$WORK/t$idx"; mkdir -p "$tdir"
  log "── 판 $idx ($kind) 반출 주기 ${iv}s"
  docker rm -f "$SRC" "$DST" >/dev/null 2>&1 || true
  start_src

  # ① writer — 상실까지 돈다. 프로시저 시간은 넉넉히 주고 상실로 끊는다
  local i pids=()
  # 🔴 writer 는 소스 «밖» 의 클라이언트 컨테이너에서 돈다. 소스 컨테이너 안에서 돌리면(docker exec) 상실 때
  #    클라이언트도 같이 죽어 버퍼에 남은 «커밋 성공» 기록이 사라진다 — 스모크에서 받은 수(1,800)보다 복구된 수
  #    (1,915)가 많게 나와서 잡혔다. --unbuffered 로 줄마다 내보낸다.
  for i in $(seq 1 "$CONNS"); do
    docker run --rm --network "$NET" "$IMG" mysql -h "$SRC" -uroot -p"$PW" --unbuffered -N -B \
      -e "CALL lab.load_run($((100000 + i)), $ROWS_PER_TX, 36000);" > "$tdir/w_$i.tsv" 2>/dev/null &
    pids+=($!)
  done
  sleep 5

  # ③ 반출기 — 판 시작부터 돈다. 운영에서 반출기는 백업과 상관없이 늘 돌고 있다.
  #    백업 뒤에 켜면 첫 파일에 백업 동안의 쓰기가 몰려 커지고, 그걸 올리는 도중 상실이 나면 백업 이후를
  #    통째로 잃는다 — 스모크 2번 판이 그랬다(올린 파일 0, 잃은 시간 66.5초). 그건 «주기» 가 아니라 «켠 시점» 의 값이다.
  shipper "$iv" "$pre" "$tdir/shipped" &
  local ship_pid=$!

  # ② 백업 → S3 (쓰는 도중)
  # 🔴 쓰기 부하 중 XtraBackup 이 가끔 «could not find redo log file with LSN» 으로 죽는다 — redo 용량(2G)을
  #    늘린 뒤에도 시작 몇 초 만에 났다. 8.0 계열 XtraBackup 은 8.0.35 가 마지막이라 서버(8.0.46)와 버전이 다르고,
  #    redo 파일 교체를 못 따라가는 경합으로 추정한다(검증 안 함). 판을 버리지 않고 최대 3번 다시 시도하며
  #    «몇 번 만에 됐나» 를 결과 칸(backup_attempts)으로 남긴다 — 그것도 운영에서 알아야 할 값이다.
  local tb0 tb1 att
  tb0=$(now_ms)
  for att in 1 2 3; do
    vol_reset "$BK_VOL"
    helper --network "$NET" -v "$SRC_VOL:/var/lib/mysql:ro" -v "$BK_VOL:/backup" "$XB_IMAGE" \
      xtrabackup --backup --host="$SRC" --user=root --password="$PW" --target-dir=/backup/b >"$tdir/xb_backup_$att.log" 2>&1 \
      && break
    log "백업 ${att}회차 실패 — $(grep -m1 ERROR "$tdir/xb_backup_$att.log" | cut -c1-140)"
    [ "$att" = 3 ] && die "백업 3회 실패 — $tdir/xb_backup_*.log"
  done
  BACKUP_ATTEMPTS=$att
  helper -v "$BK_VOL:/backup" "$XB_IMAGE" bash -c 'cd /backup && tar cf b.tar b && rm -rf b'
  s3_put "$BK_VOL" b.tar "$pre/backup/b.tar"
  tb1=$(now_ms)


  # ④ 상실 시각 — 백업 뒤 2×주기 + 주기 안의 오프셋(판마다 다르게: idx 를 섞어 0~1 사이)
  local frac off
  frac=$(awk -v s="$idx" 'BEGIN{srand(s*7919); printf "%.3f", rand()}')
  off=$(awk -v iv="$iv" -v f="$frac" 'BEGIN{printf "%d", 2*iv + f*iv}')
  sleep "$off"
  local t_kill; t_kill=$(now_ms)
  # 🔴 호스트 상실 — 반출기도 같은 호스트에 있으니 같이 죽는다. 그다음 컨테이너와 볼륨(데이터·binlog)을 통째로.
  #    ⚠️ 반출기 셸을 죽여도 이미 시작된 업로드 한 건은 끝까지 갈 수 있다 — 실제 상실보다 아주 조금 낙관적이다
  kill "$ship_pid" 2>/dev/null || true
  docker rm -f "$SRC" >/dev/null
  for i in "${pids[@]}"; do wait "$i" 2>/dev/null || true; done
  wait "$ship_pid" 2>/dev/null || true
  # 진행 중이던 업로드 helper 가 볼륨을 잡고 있으면 삭제가 거부된다 — 풀릴 때까지 재시도
  for i in $(seq 1 60); do docker volume rm -f "$SRC_VOL" >/dev/null 2>&1 && break; sleep 1; done
  docker volume inspect "$SRC_VOL" >/dev/null 2>&1 && die "소스 볼륨이 안 지워진다 — 상실을 못 만들었다"
  log "상실 — 백업 뒤 ${off}s (주기 안 오프셋 $frac)"

  # ⑤ 복구 — S3 만으로
  local r0 r1 r2 r3 r4 r5
  r0=$(now_ms)
  vol_reset "$DL_VOL"; vol_reset "$DST_VOL"
  s3_get "$pre/backup/b.tar" "$DL_VOL" b.tar
  r1=$(now_ms)
  helper -v "$DL_VOL:/dl" -v "$DST_VOL:/var/lib/mysql" "$XB_IMAGE" bash -c \
    'cd /dl && tar xf b.tar && xtrabackup --prepare --target-dir=/dl/b >/dl/prepare.log 2>&1 &&
     xtrabackup --copy-back --target-dir=/dl/b --datadir=/var/lib/mysql >/dl/copyback.log 2>&1 &&
     chown -R 999:999 /var/lib/mysql && cat /dl/b/xtrabackup_binlog_info' > "$tdir/binlog_info" \
    || die "prepare/copy-back 실패"
  r2=$(now_ms)
  docker run -d --name "$DST" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$DST_VOL:/var/lib/mysql" \
    "$IMG" --server-id=2 "${SERVER_OPTS[@]}" >/dev/null
  wait_ready "$DST"
  r3=$(now_ms)
  local bfile bpos files="" f
  bfile=$(awk '{print $1}' "$tdir/binlog_info"); bpos=$(awk '{print $2}' "$tdir/binlog_info")
  for f in $(s3_ls "$pre/binlog" | sort); do
    [[ "$f" < "$bfile" ]] && continue
    s3_get "$pre/binlog/$f" "$DL_VOL" "binlog/$f"; files="$files /dl/binlog/$f"
  done
  r4=$(now_ms)
  if [ -n "$files" ]; then
    helper -i -v "$DL_VOL:/dl:ro" "$XB_IMAGE" mysqlbinlog --start-position="$bpos" $files \
      | docker exec -i "$DST" mysql -uroot -p"$PW" 2>"$tdir/replay.err"
  fi
  r5=$(now_ms)

  # ⑥ 잃은 양 — 세션마다 «클라이언트가 커밋 성공을 받은 version» ↔ «복구된 version»
  local s acked restored lost_tx=0 last_ts=0 v_ts sum_acked=0 sum_rest=0
  for i in $(seq 1 "$CONNS"); do
    s=$((100000 + i))
    acked=$(awk -F'\t' 'NF>=3 {v=$2} END {print v+0}' "$tdir/w_$i.tsv")
    restored=$(q "$DST" "SELECT version FROM lab.session_probe WHERE id = $s;"); restored=${restored:-0}
    sum_acked=$((sum_acked + acked)); sum_rest=$((sum_rest + restored))
    [ "$acked" -gt "$restored" ] && lost_tx=$((lost_tx + acked - restored))
    v_ts=$(awk -F'\t' -v v="$restored" '$2 == v {print $3}' "$tdir/w_$i.tsv")
    [ -n "$v_ts" ] && [ "$v_ts" -gt "$last_ts" ] && last_ts=$v_ts
  done
  local rpo_s nship
  rpo_s=$(awk -v k="$t_kill" -v l="$last_ts" 'BEGIN{ if (l == 0) print "NA"; else printf "%.1f", (k*1000 - l)/1000000 }')
  nship=$(wc -l < "$tdir/shipped" | tr -d ' ')

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$idx" "$kind" "$iv" "$off" "$frac" "$nship" "$sum_acked" "$sum_rest" "$lost_tx" "$rpo_s" "$BACKUP_ATTEMPTS" \
    "$((tb1 - tb0))" "$((r1 - r0))" "$((r2 - r1))" "$((r3 - r2))" "$((r4 - r3))" "$((r5 - r4))"
  docker rm -f "$DST" >/dev/null 2>&1 || true
}

main() {
  make_base
  local tsv="$OUT/trials.tsv"
  printf 'trial\tkind\tinterval_s\tloss_after_backup_s\tloss_frac\tbinlogs_shipped\tacked_tx\trestored_tx\tlost_tx\trpo_s\tbackup_attempts\tbackup_upload_ms\tdl_backup_ms\tprepare_copyback_ms\tstart_ms\tdl_binlog_ms\treplay_ms\n' > "$tsv"
  local -a iv=($INTERVALS) plan=("${iv[0]} 버림")
  local r a
  for r in $(seq 1 "$REPS"); do
    if [ $((r % 2)) = 1 ]; then for a in "${iv[@]}"; do plan+=("$a 본판"); done
    else for (( a=${#iv[@]}-1; a>=0; a-- )); do plan+=("${iv[$a]} 본판"); done; fi
  done
  local i=0 p
  for p in "${plan[@]}"; do i=$((i + 1)); set -- $p; trial "$i" "$1" "$2" | tee -a "$tsv"; done
  {
    echo "# D0 — 무대 ($(date -u '+%Y-%m-%dT%H:%M:%SZ') UTC)"
    echo "S3_BASE=${S3_BASE:-(가짜 S3 볼륨 — 시간 값 무의미)} REDO_CAPACITY=${REDO_CAPACITY:-2G} BASE_ROWS=$BASE_ROWS INTERVALS=$INTERVALS REPS=$REPS CONNS=$CONNS ROWS_PER_TX=$ROWS_PER_TX"
    echo "MySQL $(docker run --rm "$IMG" mysqld --version 2>/dev/null) · xtrabackup $(helper "$XB_IMAGE" xtrabackup --version 2>&1 | tail -1)"
    echo "nproc=$(nproc 2>/dev/null || echo ?)"
  } > "$OUT/D0_stage.txt"
  cp -r "$WORK"/t* "$OUT/" 2>/dev/null || true
  log "완료 → $tsv"
  [ "${KEEP:-0}" = 1 ] || { docker rm -f "$SRC" "$DST" >/dev/null 2>&1; for v in "$SRC_VOL" "$BASE_VOL" "$DST_VOL" "$BK_VOL" "$DL_VOL"; do docker volume rm -f "$v" >/dev/null 2>&1; done; docker network rm "$NET" >/dev/null 2>&1; } || true
}

main "$@"
