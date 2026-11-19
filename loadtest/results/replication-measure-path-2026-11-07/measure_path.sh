#!/usr/bin/env bash
# 계측 경로 분리 — 08-17 팔 C(SOURCE_DELAY=5 → 7~11.5초)의 오차가 «계측 경로» 탓인가
# «같은 머신의 다른 프로그램(간섭)» 탓인가.
#
# 논리: 같은 무대·같은 시간대에 **경로만** 바꾼다. 간섭이 원인이면 경로를 바꿔도 오차가 남고,
# 경로가 원인이면 사라진다.
#
#   X  08-17 루프 그대로 — docker exec 로 쓰고(호스트 시각) · sleep 1 · docker exec 로 읽고 ·
#      docker exec 로 SBS 를 읽는다. 하트비트 간격 = 1초 + exec 3회.
#   Y  커넥션을 열어 둔 클라이언트(arm_y.py, mpath_client 컨테이너) — 0.1초마다 쓰고 읽음
#   Z  서버 안에서만 — 소스 프로시저가 0.1초마다 SYSDATE(6) 를 쓰고, 리플리카 프로시저가
#      0.1초마다 읽어 복제되지 않는 로컬 표에 적는다. 클라이언트 경로 0.
#
# SOURCE_DELAY=5 는 무대 셋업에서 한 번 걸고 모든 팔이 같이 쓴다. 팔마다 하트비트 표가 따로다.
# 판 순서: 버림 1(X) + 본판 9 라틴 방격(XYZ·YZX·ZXY). 판 사이에 exec 왕복을 30회 따로 잰다.
#
# 사용: bash measure_path.sh           (KEEP=1 이면 끝나도 컨테이너를 남긴다)
set -euo pipefail
# Git Bash 가 /tmp/... 같은 컨테이너 안 경로를 Windows 경로로 바꿔 넘기지 않게
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/_out}"
mkdir -p "$OUT"
cd "$HERE"

SRC=mpath_source
REP=mpath_replica
CLI=mpath_client
MP='-uroot -prepl'
ORDER="${ORDER:-X X Y Z Y Z X Z X Y}"   # 첫 판은 버림판
X_SAMPLES="${X_SAMPLES:-30}"
YZ_DUR="${YZ_DUR:-30}"                  # Y·Z 판의 읽기 구간(초)
WARM=7                                  # SOURCE_DELAY=5 뒤 첫 하트비트 도착 대기
P=(-p mpath)

log() { printf '[mpath] %s\n' "$*" >&2; }
sql()  { docker exec -i "$1" mysql $MP -N -s -e "$2" 2>/dev/null; }
sqlv() { docker exec -i "$1" mysql $MP -e "$2" 2>/dev/null; }
sqlin() { docker exec -i "$1" mysql $MP -N -s; }   # stdin 으로 — 프로시저 본문의 ; 때문에 DELIMITER 가 필요하다
rstat() { sqlv "$REP" "SHOW REPLICA STATUS\G" | awk -F': *' -v k="$1" '$0 ~ "^[ ]*"k":" {print $2; exit}'; }
now_us() { date +%s%6N; }

wait_up() {
  local c=$1 i
  for i in $(seq 1 120); do
    docker exec "$c" mysqladmin ping -h 127.0.0.1 $MP >/dev/null 2>&1 && return 0
    sleep 1
  done
  log "!! $c 가 안 뜬다"; return 1
}

snapshot_env() {   # 간섭 후보를 같이 남긴다 — 이 머신에서 그때 무엇이 돌고 있었나
  local tag=$1
  {
    echo "# $tag  $(date '+%F %T')"
    docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
    echo
    docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
  } > "$OUT/env_$tag.txt"
}

setup() {
  log "compose up"
  docker compose "${P[@]}" up -d >/dev/null
  wait_up "$SRC"; wait_up "$REP"
  local i; for i in $(seq 1 120); do docker exec "$CLI" test -f /tmp/ready 2>/dev/null && break; sleep 1; done
  docker exec "$CLI" test -f /tmp/ready || { log "!! 클라이언트 pip 설치 실패"; return 1; }

  sql "$SRC" "CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'replpw';
              GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';"
  local coord file pos
  coord=$(sql "$SRC" "SHOW MASTER STATUS;")
  file=$(printf '%s' "$coord" | awk '{print $1}'); pos=$(printf '%s' "$coord" | awk '{print $2}')
  sql "$REP" "STOP REPLICA; RESET REPLICA ALL;
              CHANGE REPLICATION SOURCE TO SOURCE_HOST='$SRC', SOURCE_PORT=3306,
                SOURCE_USER='repl', SOURCE_PASSWORD='replpw',
                SOURCE_LOG_FILE='$file', SOURCE_LOG_POS=$pos, GET_SOURCE_PUBLIC_KEY=1;
              START REPLICA;"
  # 스키마는 좌표 뒤에 — 복제를 타고 리플리카에도 생긴다
  # 새 DB 를 좌표 뒤에 만든다 — compose 의 MYSQL_DATABASE(replprobe)는 좌표 전에 생겨 리플리카에
  # 없으므로, 거기에 표를 만들면 리플리카 SQL 스레드가 멈춘다(스모크 1회차가 그랬다)
  sqlin "$SRC" <<'SQL'
    CREATE DATABASE IF NOT EXISTS mpathdb;
    CREATE TABLE IF NOT EXISTS mpathdb.hb_x (id INT PRIMARY KEY, beat_us BIGINT NOT NULL);
    CREATE TABLE IF NOT EXISTS mpathdb.hb_y (id INT PRIMARY KEY, beat_us BIGINT NOT NULL);
    CREATE TABLE IF NOT EXISTS mpathdb.hb_z (id INT PRIMARY KEY, beat DATETIME(6) NOT NULL);
    INSERT IGNORE INTO mpathdb.hb_x VALUES (1, 0);
    INSERT IGNORE INTO mpathdb.hb_y VALUES (1, 0);
    INSERT IGNORE INTO mpathdb.hb_z VALUES (1, '1970-01-02');
    DROP PROCEDURE IF EXISTS mpathdb.zbeat;
    DELIMITER //
    CREATE PROCEDURE mpathdb.zbeat(n INT)
    BEGIN
      DECLARE i INT DEFAULT 0;
      WHILE i < n DO
        UPDATE mpathdb.hb_z SET beat = SYSDATE(6) WHERE id = 1;
        DO SLEEP(0.1);
        SET i = i + 1;
      END WHILE;
    END//
    DELIMITER ;
SQL
  for i in $(seq 1 60); do
    [ "$(sql "$REP" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mpathdb' AND table_name LIKE 'hb\\_%';")" = 3 ] && break
    sleep 1
  done
  # 팔 Z 의 읽기 쪽 — 리플리카에만, binlog 없이. 읽기는 SELECT INTO(비잠금 일관 읽기)라
  # 적용 스레드와 락을 다투지 않는다
  sqlin "$REP" <<'SQL'
    SET SESSION sql_log_bin=0;
    CREATE DATABASE IF NOT EXISTS zlocal;
    -- 서버 내부 시각도 같이 적는다(스모크 2회차에서 Y·Z 도 ~6.5초가 나와 추가) —
    --   q_oc/q_eq : 마지막으로 «받은» 트랜잭션의 소스 커밋 시각 / 리플리카 수신 완료 시각
    --   a_oc/a_sa/a_ea : 마지막으로 «적용한» 트랜잭션의 소스 커밋 / 적용 시작 / 적용 끝
    CREATE TABLE IF NOT EXISTS zlocal.s (trial INT, at DATETIME(6), beat DATETIME(6),
      q_oc DATETIME(6), q_eq DATETIME(6), a_oc DATETIME(6), a_sa DATETIME(6), a_ea DATETIME(6));
    DROP PROCEDURE IF EXISTS zlocal.zread;
    DELIMITER //
    CREATE PROCEDURE zlocal.zread(t INT, n INT)
    BEGIN
      DECLARE i INT DEFAULT 0;
      DECLARE b, qoc, qeq, aoc, asa, aea DATETIME(6);
      WHILE i < n DO
        SELECT beat INTO b FROM mpathdb.hb_z WHERE id = 1;
        SELECT LAST_QUEUED_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP, LAST_QUEUED_TRANSACTION_END_QUEUE_TIMESTAMP
          INTO qoc, qeq FROM performance_schema.replication_connection_status LIMIT 1;
        SELECT LAST_APPLIED_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP, LAST_APPLIED_TRANSACTION_START_APPLY_TIMESTAMP,
               LAST_APPLIED_TRANSACTION_END_APPLY_TIMESTAMP
          INTO aoc, asa, aea FROM performance_schema.replication_applier_status_by_worker
          ORDER BY LAST_APPLIED_TRANSACTION_END_APPLY_TIMESTAMP DESC LIMIT 1;
        INSERT INTO zlocal.s VALUES (t, SYSDATE(6), b, qoc, qeq, aoc, asa, aea);
        DO SLEEP(0.1);
        SET i = i + 1;
      END WHILE;
    END//
    DELIMITER ;
SQL
  sql "$REP" "STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=5; START REPLICA;"
  sleep 2
  log "무대 준비 — SOURCE_DELAY=$(rstat SQL_Delay) · IO=$(rstat Replica_IO_Running) · SQL=$(rstat Replica_SQL_Running)"
  [ "$(rstat Replica_IO_Running)" = Yes ] && [ "$(rstat Replica_SQL_Running)" = Yes ]     || { log "!! 복제가 안 돈다 — $(rstat Last_SQL_Error)"; return 1; }
  {
    echo "mysql=$(sql "$SRC" "SELECT VERSION();")"
    echo "SQL_Delay=$(rstat SQL_Delay)"
    echo "client=$(docker exec "$CLI" python -c 'import sys,pymysql;print(sys.version.split()[0], pymysql.VERSION_STRING)')"
    echo "host_cpu=$(wmic cpu get name 2>/dev/null | sed -n 2p | tr -d '\r') nproc=$(nproc)"
  } > "$OUT/stage.txt"
}

# 08-17 팔 C 루프를 그대로 — 쓰기 시각(beat)·쓰기 exec·읽기 exec·SBS exec 를 같이 남긴다
arm_x() {
  local t=$1 i beat w0 w1 r0 r1 rep_beat s1 sbs
  for i in $(seq 1 "$X_SAMPLES"); do
    beat=$(now_us); w0=$beat
    sqlv "$SRC" "UPDATE mpathdb.hb_x SET beat_us=$beat WHERE id=1;" >/dev/null
    w1=$(now_us)
    sleep 1
    r0=$(now_us)
    rep_beat=$(sql "$REP" "SELECT beat_us FROM mpathdb.hb_x WHERE id=1;" || echo 0)
    r1=$(now_us)
    sbs=$(rstat Seconds_Behind_Source); s1=$(now_us)
    printf '%s\tX\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$t" "$i" "$beat" "${rep_beat:-0}" \
      "$w0" "$w1" "$r0" "$r1" "$s1" "${sbs:-NA}" >> "$OUT/x_samples.tsv"
  done
}

arm_y() {
  local t=$1
  docker exec "$CLI" python /work/arm_y.py "$YZ_DUR" "$WARM" | tail -n +2 \
    | awk -v t="$t" 'BEGIN{OFS="\t"} {print t, "Y", $0}' >> "$OUT/y_samples.tsv"
}

arm_z() {
  local t=$1 n_read n_beat
  n_read=$(awk -v d="$YZ_DUR" 'BEGIN{print int(d*10)}')
  n_beat=$(( n_read + (WARM + 3) * 10 ))
  docker exec -d "$SRC" mysql $MP -e "CALL mpathdb.zbeat($n_beat);"
  sleep "$WARM"
  sql "$REP" "CALL zlocal.zread($t, $n_read);"
  # 소스 쪽 루프가 끝날 때까지 — 다음 판과 겹치지 않게
  local i; for i in $(seq 1 30); do
    [ "$(sql "$SRC" "SELECT COUNT(*) FROM information_schema.processlist WHERE info LIKE 'CALL mpathdb.zbeat%';")" = 0 ] && break
    sleep 1
  done
}

exec_rtt() {   # docker exec 한 번의 왕복 — 판 사이마다 30회
  local t=$1 k a b
  for k in $(seq 1 30); do
    a=$(now_us); sql "$REP" "SELECT 1;" >/dev/null; b=$(now_us)
    printf '%s\t%s\t%s\n' "$t" "$k" "$(( b - a ))" >> "$OUT/exec_rtt.tsv"
  done
}

main() {
  : > "$OUT/x_samples.tsv"; : > "$OUT/y_samples.tsv"; : > "$OUT/exec_rtt.tsv"
  printf 'trial\tarm\ti\tbeat_us\trep_beat_us\tw0\tw1\tr0\tr1\ts1\tsbs\n' > "$OUT/x_samples.tsv"
  printf 'trial\tarm\tnow_us\tbeat_us\tlag_s\tread_rtt_ms\n' > "$OUT/y_samples.tsv"
  printf 'after_trial\tk\trtt_us\n' > "$OUT/exec_rtt.tsv"
  printf 'trial\tkind\tarm\tstart\tend\n' > "$OUT/trials.tsv"

  setup
  snapshot_env start
  local t=0 arm kind s
  for arm in $ORDER; do
    t=$((t + 1)); kind=본판; [ "$t" = 1 ] && kind=버림
    s=$(date '+%T')
    log "판 $t ($kind) — 팔 $arm"
    case $arm in X) arm_x "$t" ;; Y) arm_y "$t" ;; Z) arm_z "$t" ;; esac
    printf '%s\t%s\t%s\t%s\t%s\n' "$t" "$kind" "$arm" "$s" "$(date '+%T')" >> "$OUT/trials.tsv"
    exec_rtt "$t"
  done
  sql "$REP" "SELECT trial, 'Z', DATE_FORMAT(at,'%Y-%m-%d %H:%i:%s.%f'), DATE_FORMAT(beat,'%Y-%m-%d %H:%i:%s.%f'),
                     TIMESTAMPDIFF(MICROSECOND, beat, at)/1e6,
                     TIMESTAMPDIFF(MICROSECOND, q_oc, q_eq)/1e6,
                     TIMESTAMPDIFF(MICROSECOND, a_oc, a_sa)/1e6,
                     TIMESTAMPDIFF(MICROSECOND, a_sa, a_ea)/1e6,
                     DATE_FORMAT(a_oc,'%Y-%m-%d %H:%i:%s.%f'), DATE_FORMAT(a_sa,'%Y-%m-%d %H:%i:%s.%f')
              FROM zlocal.s ORDER BY trial, at;" \
    | { printf 'trial\tarm\tat\tbeat\tlag_s\tqueue_s\tcommit_to_apply_start_s\tapply_s\ta_oc\ta_sa\n'; cat; } > "$OUT/z_samples.tsv"
  snapshot_env end
  log "완료 → $OUT"
  [ "${KEEP:-0}" = 1 ] || docker compose "${P[@]}" down -v >/dev/null
}

main "$@"
