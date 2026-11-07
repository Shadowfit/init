#!/usr/bin/env bash
# 반동기 리플리카 2대 — 한 대가 죽으면 소스가 강등되나
#
# 설계 문서: docs/decisions/replication-lag-and-semisync.md §0-2 Q4 · docs/decisions/rpo-rto-targets.md §8 R3
#   08-17·11-07 은 리플리카 1대라 그 한 대가 죽으면 곧바로 첫 쓰기가 timeout(10초)을 물고
#   비동기로 강등됐다. 실무는 리플리카를 2대 이상 두고 «그중 N대만 받으면 커밋»
#   (rpl_semi_sync_source_wait_for_replica_count)으로 이걸 피한다고 알려져 있다 — 그걸 확인한다.
#
# 팔 (판마다 리플리카를 죽였다 살린다 · 소스 wait_for_replica_count 만 바꾼다)
#   P  count=1 · 리플리카 2 만 죽인다       — 강등 없이 버티나 (관심 팔)
#   Q  count=1 · 리플리카 1·2 둘 다 죽인다  — 강등돼야 한다 (양성 대조군: 계측이 강등을 잡는지)
#   R  count=2 · 리플리카 2 만 죽인다       — «둘 다 받아야» 면 한 대만 죽어도 강등되나
#
# 판 설계 — 버림 1(P) + 본판 9, 라틴 방격(PQR·QRP·RPQ).
# 판정 — 죽인 뒤 쓰기 3건: 첫 쓰기 지연(ms) · 소스 status · yes_tx/no_tx 증가분.
#   «버팀» = status 가 ON 으로 남고 3건 모두 yes_tx 로 센다. «강등» = status OFF 또는 no_tx 증가.
#   되살린 뒤 소스 clients=2 · status=ON 으로 돌아오는지도 본다(리플리카는 시작 옵션이라 자동 복귀가 기대값).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
mkdir -p "$OUT"

SRC=s2r_source
R1=s2r_replica1
R2=s2r_replica2
MP='-uroot -prepl'
SP=rpl_semi_sync_source
RP=rpl_semi_sync_replica

ORDER="${ORDER:-P P Q R Q R P R P Q}"   # 첫 판은 버림판

log() { printf '[s2r] %s\n' "$*" >&2; }
sql() { docker exec -i "$1" mysql $MP -N -s -e "$2" 2>/dev/null; }
# 상태 조회 — 실패하면 빈 값 대신 NA 를 낸다(11-07 rig 에 CodeRabbit 이 짚은 빈 값 산술을 피한다)
gstat() { local v; v=$(sql "$SRC" "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SP}_$1';") || true; echo "${v:-NA}"; }
num() { case "$1" in ''|*[!0-9]*) echo NA;; *) echo "$1";; esac; }
delta() { [ "$1" = NA ] || [ "$2" = NA ] && { echo NA; return; }; echo $(( $2 - $1 )); }
rstat() { docker exec -i "$1" mysql $MP -e "SHOW REPLICA STATUS\G" 2>/dev/null | awk -F': *' -v k="$2" '$0 ~ "^[ ]*"k":" {print $2; exit}'; }
now_ms() { date +%s%3N; }

wait_up() {
  local c=$1 i
  for i in $(seq 1 90); do
    docker exec "$c" mysqladmin ping -h 127.0.0.1 $MP >/dev/null 2>&1 && return 0
    sleep 1
  done
  log "!! $c 가 안 뜬다"; return 1
}

wait_repl() {
  local c=$1 i
  for i in $(seq 1 30); do
    [ "$(rstat "$c" Replica_IO_Running)" = Yes ] && [ "$(rstat "$c" Replica_SQL_Running)" = Yes ] && return 0
    sleep 1
  done
  return 1
}

# 소스가 status=ON · clients=N 이 될 때까지 최대 n 초, 1초마다 한 행씩 쓰면서
wait_on() {
  local want=$1 n=$2 i
  for i in $(seq 1 "$n"); do
    sql "$SRC" "INSERT INTO s2rdb.t (note) VALUES ('poll');" >/dev/null || true
    [ "$(gstat status)" = ON ] && [ "$(gstat clients)" = "$want" ] && { echo "$i"; return 0; }
    sleep 1
  done
  echo "-"; return 1
}

attach() {
  local rep=$1 file=$2 pos=$3
  sql "$rep" "STOP REPLICA; RESET REPLICA ALL;
              CHANGE REPLICATION SOURCE TO SOURCE_HOST='$SRC', SOURCE_PORT=3306,
                SOURCE_USER='repl', SOURCE_PASSWORD='replpw',
                SOURCE_LOG_FILE='$file', SOURCE_LOG_POS=$pos, GET_SOURCE_PUBLIC_KEY=1;
              START REPLICA;"
}

setup() {
  log "compose up"
  docker compose -f "$HERE/docker-compose.yml" -p s2r up -d >/dev/null
  wait_up "$SRC"; wait_up "$R1"; wait_up "$R2"

  sql "$SRC" "CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'replpw';
              GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';"
  local coord file pos
  coord=$(sql "$SRC" "SHOW MASTER STATUS;")
  file=$(printf '%s' "$coord" | awk '{print $1}'); pos=$(printf '%s' "$coord" | awk '{print $2}')
  [ -n "$file" ] && [ -n "$pos" ] || { log "!! 소스 좌표 못 읽음"; return 1; }
  attach "$R1" "$file" "$pos"; attach "$R2" "$file" "$pos"
  # 스키마는 좌표 뒤에 — 복제를 타고 두 리플리카로 넘어가야 한다
  sql "$SRC" "CREATE DATABASE IF NOT EXISTS s2rdb;"
  sql "$SRC" "CREATE TABLE IF NOT EXISTS s2rdb.t (id BIGINT AUTO_INCREMENT PRIMARY KEY, note VARCHAR(32), at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6));"
  wait_repl "$R1" && wait_repl "$R2" || { log "!! 복제가 안 붙었다"; return 1; }

  sql "$SRC" "INSTALL PLUGIN $SP SONAME 'semisync_source.so';" || true
  sql "$SRC" "SET GLOBAL ${SP}_enabled = 1;"
  # 리플리카는 시작 옵션으로 이미 켜져 있지만, 소스 플러그인이 방금 올라왔으니 반동기로 다시 붙인다
  sql "$R1" "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;"
  sql "$R2" "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;"
  wait_on 2 20 >/dev/null || { log "!! 시작 상태에서 clients=2 가 안 된다"; return 1; }

  {
    echo "# D0 — 무대"
    echo "# 생성: $(date -u '+%Y-%m-%dT%H:%M:%SZ') UTC"
    for v in version server_id gtid_mode binlog_format sync_binlog innodb_flush_log_at_trx_commit \
             ${SP}_enabled ${SP}_timeout ${SP}_wait_point ${SP}_wait_for_replica_count; do
      printf 'source    %-44s %s\n' "$v" "$(sql "$SRC" "SELECT @@$v;")"
    done
    for r in "$R1" "$R2"; do
      for v in version server_id read_only ${RP}_enabled; do
        printf '%-9s %-44s %s\n' "${r#s2r_}" "$v" "$(sql "$r" "SELECT @@$v;")"
      done
    done
    printf 'source    status/clients                               %s/%s\n' "$(gstat status)" "$(gstat clients)"
  } > "$OUT/D0_stage.txt"
  cat "$OUT/D0_stage.txt" >&2
}

trial() {
  local idx=$1 arm=$2 kind=$3 count victims
  case "$arm" in
    P) count=1; victims="$R2" ;;
    Q) count=1; victims="$R1 $R2" ;;
    R) count=2; victims="$R2" ;;
  esac
  log "── 판 $idx ($kind) 팔 $arm  count=$count  죽일 것: $victims"

  # 0. 시작 상태 — count 설정 → 두 리플리카가 반동기로 붙어 status=ON · clients=2
  sql "$SRC" "SET GLOBAL ${SP}_wait_for_replica_count = $count;"
  local pre_on pre
  pre_on=$(wait_on 2 20) || true
  pre="$(gstat status)/$(gstat clients)"

  # 1. 죽이고 쓴다 — 쓰기 3건, 각각 지연을 잰다
  local v; for v in $victims; do docker stop "$v" >/dev/null; done
  local yes0 no0 w0 w1 ms1 ms2 ms3
  yes0=$(num "$(gstat yes_tx)"); no0=$(num "$(gstat no_tx)")
  w0=$(now_ms); sql "$SRC" "INSERT INTO s2rdb.t (note) VALUES ('down1');"; w1=$(now_ms); ms1=$((w1 - w0))
  w0=$(now_ms); sql "$SRC" "INSERT INTO s2rdb.t (note) VALUES ('down2');"; w1=$(now_ms); ms2=$((w1 - w0))
  w0=$(now_ms); sql "$SRC" "INSERT INTO s2rdb.t (note) VALUES ('down3');"; w1=$(now_ms); ms3=$((w1 - w0))
  local down_status down_clients dyes dno verdict
  down_status=$(gstat status); down_clients=$(gstat clients)
  dyes=$(delta "$yes0" "$(num "$(gstat yes_tx)")"); dno=$(delta "$no0" "$(num "$(gstat no_tx)")")
  if [ "$down_status" = ON ] && [ "$dyes" = 3 ] && [ "$dno" = 0 ]; then verdict=버팀
  elif [ "$down_status" = OFF ] || { [ "$dno" != NA ] && [ "$dno" -gt 0 ]; }; then verdict=강등
  else verdict=판정불가; fi

  # 2. 되살린다 — 시작 옵션이라 추가 조작 없이 clients=2 로 돌아오는지
  for v in $victims; do docker start "$v" >/dev/null; done
  for v in $victims; do wait_up "$v"; done
  sql "$R1" "START REPLICA;" || true; sql "$R2" "START REPLICA;" || true
  local back_s post
  back_s=$(wait_on 2 30) || true
  post="$(gstat status)/$(gstat clients)"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s/%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$idx" "$kind" "$arm" "$count" "$(echo $victims | sed 's/s2r_//g; s/ /+/g')" "$pre" \
    "$ms1" "$ms2" "$ms3" "$down_status" "$down_clients" "$dyes" "$dno" "$verdict" "$back_s" "$post"
}

main() {
  setup
  local tsv="$OUT/trials.tsv"
  printf 'trial\tkind\tarm\tcount\tkilled\tpre_status/clients\tw1_ms\tw2_ms\tw3_ms\tdown_status/clients\tyes_tx_delta\tno_tx_delta\tverdict\tback_s\tpost_status/clients\n' > "$tsv"
  local i=0 arm kind
  for arm in $ORDER; do
    i=$((i + 1))
    kind=$([ "$i" = 1 ] && echo 버림 || echo 본판)
    trial "$i" "$arm" "$kind" | tee -a "$tsv"
  done
  log "완료 → $tsv"
  [ "${KEEP:-0}" = 1 ] || docker compose -f "$HERE/docker-compose.yml" -p s2r down -v >/dev/null
}

main "$@"
