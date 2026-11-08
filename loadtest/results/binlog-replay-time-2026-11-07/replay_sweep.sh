#!/usr/bin/env bash
# binlog 재생 시간 — 백업 이후 쌓인 binlog 를 되살리는 데 얼마 걸리나
#
# 설계 문서: docs/decisions/rpo-rto-targets.md §6-2 · §8 R1
#   구성 2(백업 + binlog 서버 밖 반출)의 RTO = 새 서버 + 백업 붓기(물리 약 174초, 08-13 실측) + **binlog 재생**.
#   마지막 항이 미측정이라 «리플리카가 필요한가»(Q5)를 못 가른다. 그 항을 «양별 시간» 으로 잰다.
#
# 팔 (재생 방법)
#   M1  mysqlbinlog | mysql            — 표준 PITR. 한 줄로 순서대로 재생
#   M2  binlog 를 릴레이 로그로 넣고 복제 SQL 스레드로 병렬 적용(replica_parallel_workers)
#       — 알려진 «RTO 를 줄이는 손잡이» 를 이 무대에서 확인한다
# 단계 (binlog 양) — LEVELS_MB (기본 512 2048 8192). 4배씩이라 «양에 비례하나» 를 본다.
#   writer 는 실제 JSON(≈2KB/행)을 넣는다 — DAU 1,000 가정(연 1억 행)이면 하루 약 0.5GB 라 세 단계가
#   대략 «백업 간격 1·4·16일치» 다(추정 — 가정 위의 환산이지 실측 아님)
#   binlog 는 한 번만 만들고 단계 경계마다 writer 를 멈춰 FLUSH 한다 → 단계 k 의 재생 대상은
#   «첫 파일부터 경계 k 까지» 의 파일 묶음(앞 단계를 포함하는 접두)이다.
# 판 — 버림 1 + 본판 REPS(기본 3) × 단계 × 팔. 판마다 복구 서버를 **같은 기준 datadir 사본**에서 새로 띄운다.
#
# 🔴 시간에 넣는 것 / 안 넣는 것
#   넣음:  M1 = mysqlbinlog 디코드 + mysql 적용 (파이프 전체)
#          M2 = SQL 스레드 시작 → 마지막 릴레이 로그 끝까지 적용 (apply_s)
#   따로:  M2 의 binlog → 릴레이 로그 복사·재기동 시간 (prep_s) — 실제 복구에서도 드는 비용이라 따로 적는다
#   안 넣음: 기준 datadir 사본 붓기 — 그건 08-13 이 «백업 붓기» 로 이미 쟀다
#
# 검증 — 재생 뒤 pose_data_scale 의 COUNT(*)·MAX(id) 와 session_probe 의 CHECKSUM 이 경계에서 찍어 둔 값과 같아야 한다.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
WORK="${WORK:-$HERE/_work}"
mkdir -p "$OUT" "$WORK"

IMG=${MYSQL_IMAGE:-mysql:8.0}
XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}   # mysqlbinlog 은 mysql:8.0 공식 이미지에 없다(백업 probe.sh:234)
PW=repl
SRC=rp_src; DST=rp_dst
SRC_VOL=rp_src_data; BASE_VOL=rp_base_data; DST_VOL=rp_dst_data
NET=rp_net

BASE_ROWS=${BASE_ROWS:-200000}          # 기준 무대 행 수 (EC2 는 따로 준다)
LEVELS_MB=${LEVELS_MB:-"512 2048 8192"}
REPS=${REPS:-3}
CONNS=${CONNS:-15}                      # 08-22 복제 라운드 writer 와 같은 모양
ROWS_PER_TX=${ROWS_PER_TX:-25}
WRITE_CHUNK_S=${WRITE_CHUNK_S:-20}      # writer 를 이 단위로 돌리며 binlog 크기를 확인한다
WORKERS=${WORKERS:-4}                   # M2 replica_parallel_workers (8.0.27+ 기본값과 같다)
SERVER_OPTS=(--log-bin=binlog --binlog-format=ROW --gtid-mode=OFF --enforce-gtid-consistency=OFF
             --innodb-flush-log-at-trx-commit=1 --sync-binlog=1
             --innodb-buffer-pool-size=${BUFFER_POOL:-1G})

log() { printf '[replay] %s\n' "$*" >&2; }
die() { log "🔴 $*"; exit 1; }
q()  { docker exec -i "$1" mysql -uroot -p"$PW" -N -s -e "$2" 2>/dev/null; }
qv() { docker exec -i "$1" mysql -uroot -p"$PW" -e "$2" 2>/dev/null; }
now_ms() { date +%s%3N; }
source "$HERE/_writer_common.sh"
rstat() { qv "$DST" "SHOW REPLICA STATUS\G" | awk -F': *' -v k="$1" '$0 ~ "^[ ]*"k":" {print $2; exit}'; }

# 쿼리가 실제로 성공할 때까지 — mysqladmin ping 은 엔트리포인트의 임시 서버에 붙어 오판한다(백업 probe.sh 교훈)
wait_ready() {
  local c=$1 i
  # 🔴 SELECT 1 로 기다리면 안 된다 — 엔트리포인트의 초기화용 임시 서버(--skip-networking)도 소켓으로는 받아서
  #    «떴다» 고 오판하고, 그 뒤 재시작 중에 스키마 생성이 실패한다(스모크가 실제로 그렇게 죽었다).
  #    임시 서버는 skip_networking=1 이므로 0 이 될 때까지 기다린다.
  for i in $(seq 1 180); do [ "$(q "$c" "SELECT @@skip_networking;")" = 0 ] && return 0; sleep 1; done
  die "$c 가 3분 안에 쿼리를 안 받는다"
}

vol_copy() {  # $1 → $2 (볼륨 통째 복사). reflink 를 끈다 — 08-14 에 XFS reflink 가 복사를 «안 한» 채 끝난 걸 잡았다
  docker volume rm -f "$2" >/dev/null 2>&1 || true
  docker volume create "$2" >/dev/null
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$1:/from:ro" -v "$2:/to" "$XB_IMAGE" \
    bash -c 'cp -a --reflink=never /from/. /to/ && rm -f /to/auto.cnf' >/dev/null
}

# ─────────────────────────────────────────────────────────────────────────
# 1. 기준 무대 — 소스를 띄우고 BASE_ROWS 를 시딩한 뒤 datadir 를 BASE_VOL 로 떠 둔다
# ─────────────────────────────────────────────────────────────────────────
make_base() {
  log "기준 무대 — $BASE_ROWS 행"
  docker rm -f "$SRC" "$DST" >/dev/null 2>&1 || true
  docker network create "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$SRC_VOL" >/dev/null 2>&1 || true
  docker run -d --name "$SRC" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$SRC_VOL:/var/lib/mysql" \
    "$IMG" --server-id=1 "${SERVER_OPTS[@]}" >/dev/null
  wait_ready "$SRC"

  q "$SRC" "
    CREATE DATABASE lab;
    CREATE TABLE lab.pose_data_scale (
      id bigint NOT NULL AUTO_INCREMENT,
      session_id bigint NOT NULL,
      timestamp_sec double NOT NULL,
      joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL,
      sync_rate double DEFAULT NULL,
      is_correct tinyint(1) DEFAULT 1,
      feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id, created_at)
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

  # 시딩 — 1만 행씩. 시딩 동안만 내구성을 늦췄다가 되돌린다(백업 rig 의 규약)
  q "$SRC" "SET GLOBAL innodb_flush_log_at_trx_commit=2; SET GLOBAL sync_binlog=0;"
  local done_rows=0
  while [ "$done_rows" -lt "$BASE_ROWS" ]; do
    q "$SRC" "INSERT INTO lab.pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)
              SELECT 1 + FLOOR(n / 750), (n % 750) * 1.2, '{}', 75.0, 1, 'ok' FROM lab._n;"
    done_rows=$((done_rows + 10000))
  done
  q "$SRC" "SET GLOBAL innodb_flush_log_at_trx_commit=1; SET GLOBAL sync_binlog=1;"

  # writer — 실제 JSON 페이로드(_writer_common.sh). 기준 무대(위 시딩)는 '{}' 그대로다 — 기준 크기는
  # 재생 대상이 아니라 «재생이 얹히는 테이블» 이라 페이로드보다 행 수·인덱스 깊이가 중요하다
  setup_writer "$SRC"

  # 기준 시점 — 여기서 binlog 를 닫는다. 이후 열리는 파일부터가 «백업 이후» 다
  q "$SRC" "FLUSH BINARY LOGS;"
  FIRST_FILE=$(q "$SRC" "SHOW MASTER STATUS;" | awk '{print $1}')
  BASE_COUNT=$(q "$SRC" "SELECT COUNT(*) FROM lab.pose_data_scale;")
  log "기준 $BASE_COUNT 행 · 재생 시작 파일 $FIRST_FILE"

  docker stop "$SRC" >/dev/null
  vol_copy "$SRC_VOL" "$BASE_VOL"      # 기준 datadir — 판마다 복구 서버가 여기서 출발한다
  docker start "$SRC" >/dev/null; wait_ready "$SRC"
  # 재기동이 binlog 를 하나 더 연다 — 기준 사본은 그 앞이라 «재생 대상» 은 FIRST_FILE 부터 그대로다
}

binlog_bytes_since() {  # FIRST_FILE 이후 파일 크기 합
  q "$SRC" "SHOW BINARY LOGS;" | awk -v f="$FIRST_FILE" '$1 >= f {s += $2} END {print s+0}'
}

# ─────────────────────────────────────────────────────────────────────────
# 2. binlog 생성 — 단계 경계마다 멈추고 FLUSH, 그 시점의 파일 목록·검증값을 적는다
# ─────────────────────────────────────────────────────────────────────────
make_binlog() {
  local lv target bytes i
  : > "$OUT/levels.tsv"
  printf 'level_mb\tbinlog_bytes\tfiles\tcount\tmax_id\tprobe_checksum\twrite_s\n' > "$OUT/levels.tsv"
  local t_all=$(date +%s)
  for lv in $LEVELS_MB; do
    target=$((lv * 1024 * 1024))
    while :; do
      bytes=$(binlog_bytes_since)
      [ "$bytes" -ge "$target" ] && break
      for i in $(seq 1 "$CONNS"); do
        q "$SRC" "CALL lab.load_run($((100000 + i)), $ROWS_PER_TX, $WRITE_CHUNK_S);" >/dev/null &
      done
      wait
    done
    q "$SRC" "FLUSH BINARY LOGS;"
    local files cnt mx ck
    # 재생 대상 = FIRST_FILE 부터 «지금 막 닫힌» 파일까지 (FLUSH 가 연 새 파일은 빼고)
    files=$(q "$SRC" "SHOW BINARY LOGS;" | awk -v f="$FIRST_FILE" '$1 >= f {print $1}' | sed '$d' | tr '\n' ' ')
    cnt=$(q "$SRC" "SELECT COUNT(*) FROM lab.pose_data_scale;")
    mx=$(q "$SRC" "SELECT MAX(id) FROM lab.pose_data_scale;")
    ck=$(q "$SRC" "CHECKSUM TABLE lab.session_probe;" | awk '{print $2}')
    bytes=$(q "$SRC" "SHOW BINARY LOGS;" | awk -v f="$FIRST_FILE" '$1 >= f {print $2}' | sed '$d' | awk '{s+=$1} END {print s+0}')
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$lv" "$bytes" "$files" "$cnt" "$mx" "$ck" "$(( $(date +%s) - t_all ))" >> "$OUT/levels.tsv"
    log "단계 ${lv}MB — 실제 $bytes 바이트 · 파일 $(echo $files | wc -w)개 · $cnt 행"
  done
}

# ─────────────────────────────────────────────────────────────────────────
# 3. 판 — 복구 서버를 기준 사본에서 띄우고 단계 k 까지 재생
# ─────────────────────────────────────────────────────────────────────────
start_dst() {  # $@ = 추가 서버 옵션
  docker rm -f "$DST" >/dev/null 2>&1 || true
  docker run -d --name "$DST" --network "$NET" -e MYSQL_ROOT_PASSWORD="$PW" -v "$DST_VOL:/var/lib/mysql" \
    "$IMG" --server-id=2 "${SERVER_OPTS[@]}" "$@" >/dev/null
  wait_ready "$DST"
}

verify() {  # $1 = 기대 count, $2 = max_id, $3 = checksum → OK/FAIL 사유
  local c m k
  c=$(q "$DST" "SELECT COUNT(*) FROM lab.pose_data_scale;")
  m=$(q "$DST" "SELECT MAX(id) FROM lab.pose_data_scale;")
  k=$(q "$DST" "CHECKSUM TABLE lab.session_probe;" | awk '{print $2}')
  if [ "$c" = "$1" ] && [ "$m" = "$2" ] && [ "$k" = "$3" ]; then echo OK; else echo "FAIL(count=$c/$1,max=$m/$2,ck=$k/$3)"; fi
}

run_m1() {  # $1 = 파일 목록
  local f args=() t0 t1
  for f in $1; do args+=("/src/$f"); done
  start_dst
  t0=$(now_ms)
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -i --network "$NET" -v "$SRC_VOL:/src:ro" "$XB_IMAGE" \
    mysqlbinlog "${args[@]}" | docker exec -i "$DST" mysql -uroot -p"$PW" 2>"$WORK/m1_apply.err"
  t1=$(now_ms)
  echo "$(( t1 - t0 ))"
}

run_m2() {  # $1 = 파일 목록 → "prep_ms apply_ms"
  local f n=0 t0 t1 t2 last
  # 3-1. binlog 를 복구 서버 datadir 에 릴레이 로그 이름으로 넣는다 (서버를 멈춘 채)
  start_dst; docker stop "$DST" >/dev/null
  t0=$(now_ms)
  local cmd="rm -f /dst/rp-relay.*; : > /dst/rp-relay.index;"
  for f in $1; do
    n=$((n + 1)); last=$(printf 'rp-relay.%06d' "$n")
    cmd="$cmd cp --reflink=never /src/$f /dst/$last && echo ./$last >> /dst/rp-relay.index;"
  done
  cmd="$cmd chown -R 999:999 /dst/rp-relay.*"
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$SRC_VOL:/src:ro" -v "$DST_VOL:/dst" "$XB_IMAGE" bash -c "$cmd" >/dev/null
  docker rm -f "$DST" >/dev/null
  start_dst --relay-log=rp-relay --skip-replica-start --relay-log-recovery=OFF \
            --replica-parallel-workers="$WORKERS" --replica-preserve-commit-order=ON
  # 이 판에서 실제로 돈 병렬 설정을 남긴다 — 이름만 보고 «병렬로 돌았다» 고 적지 않는다
  q "$DST" "SELECT @@replica_parallel_workers, @@replica_parallel_type, @@replica_preserve_commit_order;" >> "$WORK/m2_settings.txt"
  q "$DST" "CHANGE REPLICATION SOURCE TO RELAY_LOG_FILE='rp-relay.000001', RELAY_LOG_POS=4, SOURCE_HOST='dummy';" \
    || die "M2 — 릴레이 로그를 못 물렸다"
  t1=$(now_ms)
  # 3-2. SQL 스레드만 돌린다.
  # 끝 판정은 «원본 binlog 좌표» 로 한다. 🔴 릴레이 로그 파일명으로 보면 안 된다 — 적용을 마친 릴레이 파일은
  #    relay_log_purge 로 지워지고 새 파일이 열려서, «마지막 릴레이 파일의 끝» 이 영영 안 온다(스모크 1회차가 그렇게 멈췄다).
  #    완료 = 적용 좌표(Relay_Source_Log_File·Exec_Source_Log_Pos)가 마지막 원본 파일의 끝을 지났고
  #          + 조정자가 «다 읽었다» 상태 + 모든 워커가 적용 중인 트랜잭션이 없다.
  local lastsrc endpos
  lastsrc=$(echo $1 | awk '{print $NF}')
  endpos=$(MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$SRC_VOL:/src:ro" "$XB_IMAGE" stat -c %s "/src/$lastsrc")
  q "$DST" "START REPLICA SQL_THREAD;"
  while :; do
    local sf sp st state busy
    sf=$(rstat Relay_Source_Log_File); sp=$(rstat Exec_Source_Log_Pos); st=$(rstat Replica_SQL_Running)
    state=$(rstat Replica_SQL_Running_State)
    [ "$st" = Yes ] || die "M2 — SQL 스레드가 멈췄다 (errno=$(rstat Last_SQL_Errno)): $(rstat Last_SQL_Error)"
    if [[ "$sf" > "$lastsrc" ]] || { [ "$sf" = "$lastsrc" ] && [ "${sp:-0}" -ge "$endpos" ]; }; then
      if [[ "$state" == *"read all relay log"* ]]; then
        busy=$(q "$DST" "SELECT COUNT(*) FROM performance_schema.replication_applier_status_by_worker WHERE APPLYING_TRANSACTION <> '';")
        [ "${busy:-1}" = 0 ] && break
      fi
    fi
    sleep 0.5
  done
  t2=$(now_ms)
  q "$DST" "STOP REPLICA SQL_THREAD;"
  echo "$(( t1 - t0 )) $(( t2 - t1 ))"
}

main() {
  make_base
  make_binlog
  local tsv="$OUT/trials.tsv"
  printf 'trial\tkind\tmethod\tlevel_mb\tbinlog_bytes\tprep_ms\tapply_ms\tverify\n' > "$tsv"
  # 판 순서 — 버림 1(M1, 가장 작은 단계) 뒤, 반복마다 팔 순서를 뒤집고 단계 순서를 돌린다
  local -a lv=($LEVELS_MB) plan=("M1 ${lv[0]} 버림") r k m order
  for r in $(seq 1 "$REPS"); do
    if [ $((r % 2)) = 1 ]; then order="M1 M2"; else order="M2 M1"; fi
    for k in $(seq 0 $((${#lv[@]} - 1))); do
      local L=${lv[$(( (k + r - 1) % ${#lv[@]} ))]}
      for m in $order; do plan+=("$m $L 본판"); done
    done
  done
  local i=0 p
  for p in "${plan[@]}"; do
    i=$((i + 1)); set -- $p; m=$1; L=$2; kind=$3
    local row files cnt mx ck bytes prep apply res
    row=$(awk -F'\t' -v l="$L" '$1 == l' "$OUT/levels.tsv")
    bytes=$(echo "$row" | cut -f2); files=$(echo "$row" | cut -f3)
    cnt=$(echo "$row" | cut -f4); mx=$(echo "$row" | cut -f5); ck=$(echo "$row" | cut -f6)
    log "── 판 $i ($kind) $m · ${L}MB"
    vol_copy "$BASE_VOL" "$DST_VOL"
    if [ "$m" = M1 ]; then prep=0; apply=$(run_m1 "$files")
    else res=$(run_m2 "$files"); prep=${res% *}; apply=${res#* }; fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$i" "$kind" "$m" "$L" "$bytes" "$prep" "$apply" "$(verify "$cnt" "$mx" "$ck")" | tee -a "$tsv"
    docker rm -f "$DST" >/dev/null
  done
  {
    echo "# D0 — 무대 ($(date -u '+%Y-%m-%dT%H:%M:%SZ') UTC)"
    for v in version innodb_buffer_pool_size innodb_flush_log_at_trx_commit sync_binlog binlog_format \
             binlog_transaction_dependency_tracking max_binlog_size; do
      printf 'source  %-40s %s\n' "$v" "$(q "$SRC" "SELECT @@$v;")"
    done
    echo "BASE_ROWS=$BASE_ROWS LEVELS_MB=$LEVELS_MB REPS=$REPS CONNS=$CONNS ROWS_PER_TX=$ROWS_PER_TX WORKERS=$WORKERS"
    echo "M2 판별 실제 병렬 설정 (workers · type · preserve_commit_order):"; sort -u "$WORK/m2_settings.txt" 2>/dev/null
    echo "nproc=$(nproc 2>/dev/null || echo ?)"
  } > "$OUT/D0_stage.txt"
  log "완료 → $tsv"
  [ "${KEEP:-0}" = 1 ] || { docker rm -f "$SRC" "$DST" >/dev/null 2>&1; docker volume rm -f "$SRC_VOL" "$BASE_VOL" "$DST_VOL" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1; } || true
}

main "$@"
