#!/bin/bash
# 페일오버 때 앱이 새 DB 로 넘어가기까지 — 앱 박스에서 돈다 (docs/decisions/failover-app-reconnect.md)
#
#   후보  A  주소 바꾸고 앱 재시작 (systemd 유닛의 DB_HOST 를 새 IP 로 → restart)
#         B  DNS 이름 바꾸기 (앱은 db.failover.internal 로 붙는다 → 앱 박스 dnsmasq 레코드만 바꾼다)
#   흉내  K1 원본 DB 프로세스 죽이기 (docker kill — 원본 박스 OS 가 살아 있어 연결이 바로 끊긴다)
#         K2 원본 DB 네트워크 끊기 (원본 박스에서 3306 을 들고 나는 패킷을 버린다 — 아무 응답도 없다)
#
# 한 판: 두 DB 를 새로 띄워 복제 연결 → 앱을 원본에 붙여 재시작(Flyway) → 계정 준비 → 탐침 시작 →
#        PRE_S 뒤 장애 → 바로 승격(①알아챔·②결정 = 0, §4) → 바로 전환 → «연속 성공 STABLE_S 초» 가 되면
#        새 요청을 멈추고, 나간 요청은 CLIENT_TIMEOUT_S 까지 기다린다 → 잃은 쓰기 대조
#
# 🔴 앱 타임아웃은 지금 설정 그대로다(§6-1 R-b) — Hikari connection-timeout 30초, Connector/J 의
#    connectTimeout·socketTimeout 은 지정 안 함. 이 rig 은 그걸 바꾸지 않는다.
# 🔴 DNS 는 Route 53 이 아니라 앱 박스의 dnsmasq 다(§6-1 R-a) — 레코드 TTL 0. 레코드 전파 시간이 빠진다.
#
# 사용: failover.sh prep   (부트스트랩 전 — 앱이 처음 뜰 DB 를 준비)
#       failover.sh run
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT=${OUT:-/root/rig_out}
DB1=${DB1:?DB1 사설 IP}
DB2=${DB2:?DB2 사설 IP}
PW=${PW:-1234}                       # bootstrap.sh 의 기본 PW 와 같아야 앱이 붙는다
NAME=db.failover.internal
HOSTS_FILE=/etc/failover-hosts
SVC=/etc/systemd/system/shadowfit-app.service
BASE=http://127.0.0.1:8080
RATE=${RATE:-10}
PRE_S=${PRE_S:-30}
STABLE_S=${STABLE_S:-30}
POST_CAP_S=${POST_CAP_S:-1200}
# 리눅스 기본 TCP 재전송 한도(tcp_retries2=15, 약 924초)보다 길게 — 매달린 요청이 서버 쪽에서 먼저 끝나게
CLIENT_TIMEOUT_S=${CLIENT_TIMEOUT_S:-1000}
ACCOUNTS=${ACCOUNTS:-4}
# 첫 네 판은 칸마다 하나씩 버림. 그 뒤 세 바퀴는 칸 순서를 바꿔 «칸» 과 «판 순서» 가 섞이지 않게
TRIALS=${TRIALS:-"A:K1 B:K1 A:K2 B:K2  B:K2 A:K2 B:K1 A:K1  A:K2 B:K1 B:K2 A:K1  B:K1 A:K1 A:K2 B:K2"}
BURN=${BURN:-4}
SSH=(ssh -i /root/.ssh/rig -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=5)

mkdir -p "$OUT"
log() { printf '[failover %s] %s\n' "$(date +%T)" "$*" >&2; }
die() { log "🔴 $*"; exit 1; }
now_ms() { date +%s%3N; }
rsh() { local h=$1; shift; "${SSH[@]}" "root@$h" "$@"; }
# SQL 은 표준입력으로 넘긴다 — ssh 따옴표 안에 SQL 을 넣으면 따옴표가 두 번 풀린다
dq() { local h=$1; printf '%s\n' "$2" | timeout "${3:-60}" "${SSH[@]}" "root@$h" "docker exec -i -e MYSQL_PWD=$PW mdb mysql -uroot -N -s 2>/dev/null"; }

db_start() {  # $1 host $2 server-id — 매 판 새로 (볼륨까지 지움)
  rsh "$1" "iptables -F INPUT; iptables -F OUTPUT; docker rm -f mdb >/dev/null 2>&1; docker volume rm -f mvol >/dev/null 2>&1;
    docker run -d --name mdb --network host -e MYSQL_ROOT_PASSWORD=$PW -v mvol:/var/lib/mysql mysql:8.0 \
      --server-id=$2 --log-bin=binlog --binlog-format=ROW --gtid-mode=ON --enforce-gtid-consistency=ON \
      --innodb-flush-log-at-trx-commit=1 --sync-binlog=1 --innodb-buffer-pool-size=2G >/dev/null" || die "$1 DB 기동 실패"
  local i
  for i in $(seq 1 120); do [ "$(dq "$1" 'SELECT @@skip_networking;' 10)" = 0 ] && return 0; sleep 2; done
  die "$1 DB 가 4분 안에 쿼리를 안 받는다"
}

db_pair() {
  db_start "$DB1" 1 & local p1=$!
  db_start "$DB2" 2 & local p2=$!
  wait $p1 || die "DB1 준비 실패"; wait $p2 || die "DB2 준비 실패"
  dq "$DB2" "CHANGE REPLICATION SOURCE TO SOURCE_HOST='$DB1', SOURCE_USER='root', SOURCE_PASSWORD='$PW',
             SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA;
             SET GLOBAL read_only=1; SET GLOBAL super_read_only=1;" >/dev/null || die "복제 연결 실패"
  local i ok=0
  for i in $(seq 1 30); do
    [ "$(dq "$DB2" "SELECT SERVICE_STATE FROM performance_schema.replication_connection_status;")" = ON ] && { ok=1; break; }
    sleep 1
  done
  [ "$ok" = 1 ] || die "리플리카 IO 스레드가 안 붙는다"
  # 앱 계정·DB 는 복제를 붙인 «뒤» 원본에서만 만든다 — 이미지 초기화(MYSQL_USER·MYSQL_DATABASE)로 두 대에 따로
  # 만들면 원본 것이 복제돼 넘어갈 때 리플리카에서 «이미 있다» 로 SQL 스레드가 설 수 있다(promote.sh 와 같은 방식)
  dq "$DB1" "CREATE DATABASE shadowfit; CREATE USER 'shadowfit'@'%' IDENTIFIED BY '$PW';
             GRANT ALL ON shadowfit.* TO 'shadowfit'@'%';" >/dev/null || die "앱 계정 생성 실패"
  local g; g=$(dq "$DB1" "SELECT @@gtid_executed;" | tr -d '\n ')
  [ "$(dq "$DB2" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$g', 30);")" = 0 ] || die "리플리카가 앱 계정 생성을 못 받았다"
  [ "$(dq "$DB2" "SELECT SERVICE_STATE FROM performance_schema.replication_applier_status;")" = ON ] || die "리플리카 SQL 스레드가 섰다"
}

point_app() {  # $1 arm $2 ip — 유닛·DNS 를 고친다(재시작은 따로)
  if [ "$1" = A ]; then
    sed -i "s|^Environment=DB_HOST=.*|Environment=DB_HOST=$2|" "$SVC"
  else
    sed -i "s|^Environment=DB_HOST=.*|Environment=DB_HOST=$NAME|" "$SVC"
    echo "$2 $NAME" > "$HOSTS_FILE"
    pkill -HUP dnsmasq || die "dnsmasq 가 없다"
  fi
  systemctl daemon-reload
}

wait_health() {
  local i
  for i in $(seq 1 150); do curl -sf --max-time 2 http://127.0.0.1:9090/actuator/health >/dev/null && return 0; sleep 2; done
  return 1
}

prep() {
  db_pair
  echo "$DB1 $NAME" > "$HOSTS_FILE"; pkill -HUP dnsmasq
  local i; for i in $(seq 1 10); do [ "$(getent hosts $NAME | awk '{print $1}')" = "$DB1" ] && break; sleep 1; done
  [ "$(getent hosts $NAME | awk '{print $1}')" = "$DB1" ] || die "getent 가 $NAME 을 DB1 로 못 푼다 — 앱이 쓰는 경로가 dnsmasq 를 안 탄다"
  log "prep 끝 — DB 두 대 복제 연결, $NAME → DB1"
}

# 장애가 난 뒤 «최근 STABLE_S 초에 보낸 요청이 전부 성공으로 돌아왔다» 가 되면 0
stable() {  # $1 tsv $2 기준 시각(ms, 전환 시각)
  local now; now=$(now_ms)
  awk -F'\t' -v lo=$(( now - (STABLE_S + 3) * 1000 )) -v hi=$(( now - 3000 )) -v from="$2" -v need=$(( RATE * STABLE_S * 8 / 10 )) '
    NR>1 && $4>=lo && $4<=hi && $4>=from { n++; if ($6 != "204") bad++ }
    END { exit !(n >= need && bad == 0) }' "$1"
}

trial() {  # $1 번호 $2 arm $3 kill $4 kind
  local idx=$1 arm=$2 kill=$3 kind=$4 tdir="$OUT/t$1"
  mkdir -p "$tdir/ctrl"; rm -f "$tdir/ctrl/STOP"
  log "── 판 $idx ($kind) $arm·$kill"
  local applog_off; applog_off=$(stat -c%s /root/app.log 2>/dev/null || echo 0)

  db_pair
  point_app "$arm" "$DB1"
  systemctl restart shadowfit-app
  wait_health || die "판 $idx: 앱이 원본에 붙어 안 뜬다"
  local g; g=$(dq "$DB1" "SELECT @@gtid_executed;" | tr -d '\n ')
  [ "$(dq "$DB2" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$g', 60);")" = 0 ] || die "판 $idx: 리플리카가 스키마를 못 따라잡았다"
  python3 "$HERE/probe.py" setup "$BASE" "$ACCOUNTS" "p${idx}u" "$tdir/tokens" >&2 || die "판 $idx: 계정 준비 실패"

  python3 "$HERE/probe.py" run "$BASE" "$tdir/tokens" "$RATE" "$tdir/probe.tsv" "$tdir/ctrl" "t$idx" "$CLIENT_TIMEOUT_S" \
    > "$tdir/probe.out" 2>&1 &
  local ppid=$!
  sleep "$PRE_S"

  # ── 장애 ──
  local t_kill0 t_kill
  t_kill0=$(now_ms)
  if [ "$kill" = K1 ]; then
    rsh "$DB1" "docker kill mdb >/dev/null"
  else
    rsh "$DB1" "iptables -I INPUT -p tcp --dport 3306 -j DROP; iptables -I OUTPUT -p tcp --sport 3306 -j DROP"
  fi
  t_kill=$(now_ms)

  # ── 승격 (①② = 0: 장애 직후 바로) — 11-08 promote.sh 와 같은 순서 ──
  local t0 t1 t2 t3 recv w
  t0=$(now_ms)
  dq "$DB2" "STOP REPLICA IO_THREAD;" 300 >/dev/null || log "⚠️ IO 스레드 중지 실패/시간초과"
  t1=$(now_ms)
  recv=$(dq "$DB2" "SELECT RECEIVED_TRANSACTION_SET FROM performance_schema.replication_connection_status;" | tr -d '\n ')
  if [ -n "$recv" ]; then
    w=$(dq "$DB2" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$recv', 600);" 700)
    [ "$w" = 0 ] || log "⚠️ 적용 대기 실패 (w=$w)"
  fi
  t2=$(now_ms)
  dq "$DB2" "STOP REPLICA; RESET REPLICA ALL; SET GLOBAL super_read_only=0; SET GLOBAL read_only=0;" 120 >/dev/null || log "⚠️ 전환 SQL 실패"
  t3=$(now_ms)

  # ── 앱 전환 ──
  point_app "$arm" "$DB2"
  [ "$arm" = A ] && systemctl restart --no-block shadowfit-app
  local t_switch; t_switch=$(now_ms)

  # ── 회복 대기 ──
  local t_end status=stable
  while ! stable "$tdir/probe.tsv" "$t_switch"; do
    if [ $(( $(now_ms) - t_switch )) -gt $(( POST_CAP_S * 1000 )) ]; then status=cap; break; fi
    sleep 1
  done
  t_end=$(now_ms)
  touch "$tdir/ctrl/STOP"
  log "판 $idx: 새 요청 멈춤($status) — 나간 요청 기다리는 중(최대 ${CLIENT_TIMEOUT_S}초)"
  wait $ppid

  # ── 잃은 쓰기: 성공(204)을 받은 memo 가 새 원본(DB2)에 있나 ──
  awk -F'\t' 'NR>1 && $6=="204" {print "t'"$idx"'-" $1}' "$tdir/probe.tsv" | sort > "$tdir/acked.txt"
  dq "$DB2" "SELECT memo FROM shadowfit.daily_logs WHERE memo LIKE 't$idx-%';" 120 | sort > "$tdir/on_new.txt"
  comm -23 "$tdir/acked.txt" "$tdir/on_new.txt" > "$tdir/lost.txt"
  local n_old=NA
  [ "$kill" = K2 ] && n_old=$(dq "$DB1" "SELECT COUNT(*) FROM shadowfit.daily_logs WHERE memo LIKE 't$idx-%';" 30 || echo NA)

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$idx" "$kind" "$arm" "$kill" "$t_kill0" "$t_kill" "$((t1 - t0))" "$((t2 - t1))" "$((t3 - t2))" "$((t3 - t_kill))" \
    "$t_switch" "$t_end" "$status" "$(wc -l < "$tdir/lost.txt")" "$n_old" >> "$OUT/events.tsv"
  tail -c +"$((applog_off + 1))" /root/app.log > "$tdir/app.log" 2>/dev/null || true
  log "판 $idx 끝 — 승격 $((t3 - t_kill))ms · 회복 판정 $status · 잃은 쓰기 $(wc -l < "$tdir/lost.txt")"
}

run() {
  {
    echo "# 페일오버 앱 전환 조건 ($(date -Is))"
    echo "DB1=$DB1 DB2=$DB2 NAME=$NAME RATE=$RATE PRE_S=$PRE_S STABLE_S=$STABLE_S POST_CAP_S=$POST_CAP_S CLIENT_TIMEOUT_S=$CLIENT_TIMEOUT_S ACCOUNTS=$ACCOUNTS"
    echo "TRIALS=$TRIALS (앞 $BURN 판 버림)"
    echo "java: $(java -version 2>&1 | head -1)"
    echo "JVM DNS 캐시 설정(java.security): $(grep -hE '^#?networkaddress\.cache\.(negative\.)?ttl' "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"/conf/security/java.security 2>/dev/null | tr '\n' ' ')"
    echo "tcp_retries2=$(cat /proc/sys/net/ipv4/tcp_retries2) · tcp_syn_retries=$(cat /proc/sys/net/ipv4/tcp_syn_retries)"
    echo "앱 유닛:"; grep -E '^(Environment|ExecStart)=' "$SVC" | grep -v -E 'PASSWORD|SECRET|TOKEN'
    echo "dnsmasq: $(grep -vE '^\s*(#|$)' /etc/dnsmasq.d/failover.conf | tr '\n' ' ')"
  } > "$OUT/CONDITIONS.txt"
  printf 'trial\tkind\tarm\tkill\tt_kill0_ms\tt_kill_ms\tio_stop_ms\tapply_ms\tswitch_sql_ms\tpromoted_after_kill_ms\tt_switch_ms\tt_end_ms\tstatus\tlost_acked\trows_on_old\n' > "$OUT/events.tsv"
  local n=0 c
  for c in $TRIALS; do
    n=$((n + 1))
    trial "$n" "${c%%:*}" "${c##*:}" "$([ $n -le "$BURN" ] && echo 버림 || echo 본판)"
  done
  python3 "$HERE/analyze.py" "$OUT" > "$OUT/summary.tsv" || log "⚠️ 요약 실패 — 원자료는 남아 있다"
  log "완료 → $OUT"
}

case "${1:-}" in
  prep) prep ;;
  run)  run ;;
  *) die "prep | run" ;;
esac
