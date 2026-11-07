#!/usr/bin/env bash
# 반동기 미복귀 — 원인이 MySQL 인가 08-17 rig 의 복구 절차인가
#
# 설계 문서: docs/decisions/replication-lag-and-semisync.md §0-2 Q4
#   08-17 라운드는 «리플리카가 돌아와도 status=OFF·clients=0» 을 관측했고, 원인을
#   «rig 의 복구 절차(재기동 후 START REPLICA 만) 탓인지 갈라내지 않았다» 로 남겼다.
#
# 가설 — 08-17 rig 은 리플리카 쪽 반동기를 SET GLOBAL(메모리 전용)로 켰다
#   (../replication-2026-08-17/replication_probe.sh:276). 재기동하면 그 값이 기본값 OFF 로
#   돌아가고, 리플리카는 «반동기 아님» 으로 다시 붙는다 → 소스 clients=0.
#
# 팔 (판마다 리플리카를 죽였다 살린다)
#   A  08-17 재현   — SET GLOBAL 로 켬 · 재기동 후 START REPLICA 만
#   B  수동 재설정  — A 와 같이 켬 · 재기동 후 리플리카에서 반동기를 다시 켜고 IO 스레드 재시작
#   C  영속 설정    — SET PERSIST 로 켬 · 재기동 후 START REPLICA 만
#   D  시작 옵션    — --plugin-load-add + --rpl-semi-sync-replica-enabled=ON · 재기동 후 START REPLICA 만 (따로 돈다)
#
# 판 설계 — 버림 1(A) + 본판 9. 순서를 라틴 방격(ABC·BCA·CAB)으로 돌려 «팔» 과 «판 순서» 를
#   가른다. 판마다 RESET PERSIST 로 앞 판(특히 C)의 영속 설정이 새지 않게 한다.
#
# 판정 — 복귀 = 재기동 후 소스 status=ON 이고 clients=1 이고, 복구 뒤 쓰기가 yes_tx 로 센다.
#   원인 증거 = 재기동 직후(복구 조작 전) 리플리카의 @@rpl_semi_sync_replica_enabled 값.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
mkdir -p "$OUT"

SRC=srec_source
REP=srec_replica
MP='-uroot -prepl'
SP=rpl_semi_sync_source
RP=rpl_semi_sync_replica

ORDER="${ORDER:-A A B C B C A C A B}"   # 첫 판은 버림판
# D 팔은 리플리카 시작 옵션이 달라 따로 돈다: COMPOSE_OVERRIDE=docker-compose.armD.yml ORDER="D D D D"
COMPOSE=(-f "$HERE/docker-compose.yml")
[ -n "${COMPOSE_OVERRIDE:-}" ] && COMPOSE+=(-f "$HERE/$COMPOSE_OVERRIDE")

log() { printf '[srec] %s\n' "$*" >&2; }
sql() { docker exec -i "$1" mysql $MP -N -s -e "$2" 2>/dev/null; }
gstat() { sql "$SRC" "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SP}_$1';"; }
rstat() { docker exec -i "$REP" mysql $MP -e "SHOW REPLICA STATUS\G" 2>/dev/null | awk -F': *' -v k="$1" '$0 ~ "^[ ]*"k":" {print $2; exit}'; }
now_ms() { date +%s%3N; }

wait_up() {
  local c=$1 i
  for i in $(seq 1 90); do
    docker exec "$c" mysqladmin ping -h 127.0.0.1 $MP >/dev/null 2>&1 && return 0
    sleep 1
  done
  log "!! $c 가 안 뜬다"; return 1
}

# 리플리카 IO/SQL 이 Yes 가 될 때까지
wait_repl() {
  local i
  for i in $(seq 1 30); do
    [ "$(rstat Replica_IO_Running)" = Yes ] && [ "$(rstat Replica_SQL_Running)" = Yes ] && return 0
    sleep 1
  done
  return 1
}

# 소스 status 가 ON 이 될 때까지 최대 N 초 — 그동안 1초마다 한 행씩 쓴다
# (반동기 소스는 리플리카가 따라잡은 뒤 다음 트랜잭션에서 ON 으로 올라온다)
poll_on() {
  local n=$1 i
  for i in $(seq 1 "$n"); do
    sql "$SRC" "INSERT INTO srecdb.t (note) VALUES ('poll');" >/dev/null || true
    [ "$(gstat status)" = ON ] && { echo "$i"; return 0; }
    sleep 1
  done
  echo "-"; return 1
}

setup() {
  log "compose up"
  docker compose "${COMPOSE[@]}" -p srec up -d >/dev/null
  wait_up "$SRC"; wait_up "$REP"

  sql "$SRC" "CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'replpw';
              GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';"
  local coord file pos
  coord=$(sql "$SRC" "SHOW MASTER STATUS;")
  file=$(printf '%s' "$coord" | awk '{print $1}'); pos=$(printf '%s' "$coord" | awk '{print $2}')
  [ -n "$file" ] && [ -n "$pos" ] || { log "!! 소스 좌표 못 읽음"; return 1; }
  sql "$REP" "STOP REPLICA; RESET REPLICA ALL;
              CHANGE REPLICATION SOURCE TO SOURCE_HOST='$SRC', SOURCE_PORT=3306,
                SOURCE_USER='repl', SOURCE_PASSWORD='replpw',
                SOURCE_LOG_FILE='$file', SOURCE_LOG_POS=$pos, GET_SOURCE_PUBLIC_KEY=1;
              START REPLICA;"
  # 스키마는 좌표 뒤에 만든다 — 복제를 타고 넘어가야 리플리카에도 생긴다(08-17 rig 의 교훈)
  sql "$SRC" "CREATE DATABASE IF NOT EXISTS srecdb;"
  sql "$SRC" "CREATE TABLE IF NOT EXISTS srecdb.t (id BIGINT AUTO_INCREMENT PRIMARY KEY, note VARCHAR(32), at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6));"
  wait_repl || { log "!! 복제가 안 붙었다"; return 1; }

  # 플러그인 설치는 mysql.plugin 에 기록돼 재기동 뒤에도 로드된다 — 팔이 가르는 것은
  # «플러그인이 있나» 가 아니라 «enabled 변수가 살아남나» 다
  sql "$SRC" "INSTALL PLUGIN $SP SONAME 'semisync_source.so';" || true
  sql "$REP" "INSTALL PLUGIN $RP SONAME 'semisync_replica.so';" || true
  sql "$SRC" "SET GLOBAL ${SP}_enabled = 1;"     # 소스는 재기동하지 않는다 — 팔과 무관
  # 소스 timeout 은 08-17 과 같은 기본 10000ms 그대로

  {
    echo "# D0 — 무대"
    echo "# 생성: $(date -u '+%Y-%m-%dT%H:%M:%SZ') UTC"
    for v in version server_id gtid_mode binlog_format sync_binlog innodb_flush_log_at_trx_commit \
             ${SP}_enabled ${SP}_timeout ${SP}_wait_point ${SP}_wait_for_replica_count; do
      printf 'source   %-44s %s\n' "$v" "$(sql "$SRC" "SELECT @@$v;")"
    done
    for v in version server_id read_only; do
      printf 'replica  %-44s %s\n' "$v" "$(sql "$REP" "SELECT @@$v;")"
    done
    printf 'source   plugin %-37s %s\n' "$SP" "$(sql "$SRC" "SELECT PLUGIN_STATUS FROM information_schema.PLUGINS WHERE PLUGIN_NAME='$SP';")"
    printf 'replica  plugin %-37s %s\n' "$RP" "$(sql "$REP" "SELECT PLUGIN_STATUS FROM information_schema.PLUGINS WHERE PLUGIN_NAME='$RP';")"
  } > "$OUT/D0_stage.txt"
  cat "$OUT/D0_stage.txt" >&2
}

# 판 하나. 한 줄 TSV 를 stdout 으로 낸다.
trial() {
  local idx=$1 arm=$2 kind=$3
  log "── 판 $idx ($kind) 팔 $arm"

  # 0. 판 시작 상태로 맞춘다 — 영속 설정 제거 → 팔 방식으로 켬 → 반동기 ON 확인
  sql "$REP" "RESET PERSIST IF EXISTS ${RP}_enabled;"
  if [ "$arm" = C ]; then
    sql "$REP" "SET PERSIST ${RP}_enabled = 1;"
  elif [ "$arm" = D ]; then
    :   # 시작 옵션(--rpl-semi-sync-replica-enabled=ON)이 켠다 — 런타임 조작 없음
  else
    sql "$REP" "SET GLOBAL ${RP}_enabled = 1;"
  fi
  sql "$REP" "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;"
  wait_repl || log "!! 판 시작 복제 이상"
  local pre_on
  pre_on=$(poll_on 15) || true
  local pre_status pre_clients
  pre_status=$(gstat status); pre_clients=$(gstat clients)

  # 1. 리플리카를 죽이고 소스에 쓴다 — 첫 쓰기가 timeout 을 무는지, 강등되는지
  docker stop "$REP" >/dev/null
  local w0 w1 first_ms
  w0=$(now_ms); sql "$SRC" "INSERT INTO srecdb.t (note) VALUES ('down1');"; w1=$(now_ms)
  first_ms=$((w1 - w0))
  sql "$SRC" "INSERT INTO srecdb.t (note) VALUES ('down2'), ('down3');"
  local down_status no_tx_down
  down_status=$(gstat status); no_tx_down=$(gstat no_tx)

  # 2. 되살린다 — 복구 조작 «전» 에 리플리카 쪽 값을 찍는다(원인 증거)
  docker start "$REP" >/dev/null
  wait_up "$REP"
  local rep_enabled_after rep_plugin_after io_auto
  rep_enabled_after=$(sql "$REP" "SELECT @@${RP}_enabled;")
  rep_plugin_after=$(sql "$REP" "SELECT PLUGIN_STATUS FROM information_schema.PLUGINS WHERE PLUGIN_NAME='$RP';")
  io_auto=$(rstat Replica_IO_Running)

  # 3. 팔별 복구 절차
  sql "$REP" "START REPLICA;" || true        # 이미 돌고 있으면 경고만 난다 — 08-17 과 같은 조작
  if [ "$arm" = B ]; then
    sql "$REP" "SET GLOBAL ${RP}_enabled = 1;"
    sql "$REP" "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;"
  fi
  wait_repl || log "!! 복구 후 복제 이상"

  # 4. 복귀 판정 — 최대 20초 동안 쓰면서 ON 을 기다린다
  local yes0 on_after post_status post_clients yes_delta verdict
  yes0=$(gstat yes_tx)
  on_after=$(poll_on 20) || true
  sql "$SRC" "INSERT INTO srecdb.t (note) VALUES ('after');"
  post_status=$(gstat status); post_clients=$(gstat clients)
  yes_delta=$(( $(gstat yes_tx) - yes0 ))
  if [ "$post_status" = ON ] && [ "$post_clients" = 1 ] && [ "$yes_delta" -gt 0 ]; then verdict=복귀; else verdict=미복귀; fi

  printf '%s\t%s\t%s\t%s/%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s/%s\t%s\t%s\t%s\n' \
    "$idx" "$kind" "$arm" "$pre_status" "$pre_clients" "$first_ms" "$down_status" "$no_tx_down" \
    "$rep_enabled_after" "$rep_plugin_after" "$io_auto" "$on_after" \
    "$post_status" "$post_clients" "$yes_delta" "$verdict" "$pre_on"
}

main() {
  setup
  local tsv="$OUT/trials.tsv"
  printf 'trial\tkind\tarm\tpre_status/clients\tfirst_write_ms\tdown_status\tno_tx\trep_enabled_after_restart\trep_plugin_after_restart\tio_running_after_restart\ton_after_s\tpost_status/clients\tyes_tx_delta\tverdict\tpre_on_s\n' > "$tsv"
  local i=0 arm kind
  for arm in $ORDER; do
    i=$((i + 1))
    kind=$([ "$i" = 1 ] && echo 버림 || echo 본판)
    trial "$i" "$arm" "$kind" | tee -a "$tsv"
  done
  log "완료 → $tsv"
  [ "${KEEP:-0}" = 1 ] || docker compose "${COMPOSE[@]}" -p srec down -v >/dev/null
}

main "$@"
