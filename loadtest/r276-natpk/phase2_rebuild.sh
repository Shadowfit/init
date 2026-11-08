#!/usr/bin/env bash
# ② PK 재구성 — 지금 표(팔 A)를 자연키 PK(팔 B)로 바꾸는 DDL 의 시간과 쓰기 정지
#
# 설계: docs/decisions/r276-lock-root-cause-fix.md §5-4 ②
# 비교 기준: online-ddl AWS 08-12 (m6i.xlarge · 1,000만 행 · 차단 ALTER 68~70초 ↔ pt-osc 112~114초)
#
# 순서
#   [0] 원본 표 tpl_A 를 1,000만 행으로 깐다 (세션 16,667 × 600 — 세션 번호 순, 실제 적재 순서와 같다)
#   [1] 사전 확인 — 작은 복사본에 INPLACE·LOCK=NONE 을 던져 서버가 받는지 본다(08-12 는 1845 로 거절됐다)
#   [2] 판마다 tpl_A 를 t_rb 로 복사 → writer(0.2초마다 20행 묶음) 를 띄운 채 DDL → writer 를 멈추고 요약
#       팔 N = 서버 DDL(받으면 INPLACE·LOCK=NONE, 아니면 COPY) · 팔 P = pt-online-schema-change
#       팔 N0 = 팔 N 과 같은 DDL 을 writer 없이 — «DDL 자체 시간» 과 «쓰기 때문에 늘어난 시간» 을 가른다 (11-09 추가)
#       판 순서는 08-12 와 같다: 버림 N·P → 본판 N P P N N P
#   [3] 마지막 판의 결과 표를 r_B 로 남긴다 — ③ 읽기가 쓴다 (① 의 t_B 와 이름을 나눈다)
#
# 판마다 남기는 것: DDL 소요 · writer 최대 정지(성공 커밋 사이 최대 간격) · 실패 수 · binlog 증가 · 디스크 최대
set -uo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

SESSIONS=${SESSIONS:-16667}             # × 600 = 10,000,200 행
POOL_MB=${POOL_MB:-4096}
SEQ=${SEQ:-"discard:N discard:P r1:N r2:P r3:P r4:N r5:N r6:P"}
GAP_MS=${GAP_MS:-200}
WRITER_SECS=${WRITER_SECS:-7200}        # writer 가 이보다 먼저 끝나면 그 뒤 DDL 은 쓰기 없이 돈다 — 긴 DDL 엔 늘릴 것
PTOSC_IMAGE=${PTOSC_IMAGE:-percona/percona-toolkit:3.6.0}
OUT=${OUT:-$HERE/_out/phase2}
mkdir -p "$OUT" "$HERE/_work"

NEWKEY="DROP PRIMARY KEY, ADD PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at), DROP INDEX uk_pose_event, ADD KEY idx_id (id)"

log(){ printf '[phase2 %s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log" >&2; }
die(){ log "🔴 중단 — $*"; exit 1; }
SQL(){ docker exec -i natpk_mysql mysql -uroot -pnatpk natpk -N -s -e "$1" 2>>"$OUT/mysql_stderr.log"; }
PY(){ docker exec -i natpk_client python /work/natpk.py "$@"; }
binlog_bytes(){ SQL "SHOW BINARY LOGS" | awk '{s+=$2} END{print s+0}'; }
disk_mb(){ docker exec natpk_mysql du -sm /var/lib/mysql 2>/dev/null | awk '{print $1}'; }
pk_cols(){ SQL "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
                WHERE table_schema='natpk' AND table_name='$1' AND index_name='PRIMARY'"; }

# 앞 판(또는 끊긴 실행)의 writer 가 남아 있으면 복사·DDL 에 쓰기가 섞인다 — 판마다 먼저 끝낸다.
# 클라이언트 이미지(slim)엔 ps 가 없어 /proc 으로 찾는다.
kill_writers(){
  docker exec natpk_client sh -c 'for p in /proc/[0-9]*; do tr "\0" " " < $p/cmdline 2>/dev/null | grep -q "natpk.py writer" && kill ${p#/proc/}; done; true'
  sleep 1
}

wait_mysql(){
  local hits=0
  for _ in $(seq 1 120); do
    if SQL "SELECT 1" | grep -q 1; then hits=$((hits+1)); [ $hits -ge 2 ] && return 0; else hits=0; fi
    sleep 3
  done
  die "MySQL 이 6분 안에 준비되지 않았다"
}

log "기동 — 버퍼풀 ${POOL_MB}MB"
POOL_MB=$POOL_MB docker compose up -d >/dev/null 2>&1 || die "compose up 실패"
wait_mysql
for _ in $(seq 1 60); do docker exec natpk_client test -f /tmp/ready && break; sleep 2; done
docker exec natpk_client test -f /tmp/ready || die "client 준비 안 됨"
docker pull -q "$PTOSC_IMAGE" >/dev/null || die "pt-osc 이미지를 못 받았다"
{
  echo "# start $(date '+%F %T')"
  echo "host_cpu=$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2) nproc=$(nproc 2>/dev/null)"
  echo "mysql=$(SQL "SELECT VERSION()") pool=$(SQL "SELECT @@innodb_buffer_pool_size") ptosc=$PTOSC_IMAGE"
  echo "SESSIONS=$SESSIONS SEQ=$SEQ GAP_MS=$GAP_MS WRITER_SECS=$WRITER_SECS git=$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null)"
  echo "online_alter_log_max_size=$(SQL "SELECT @@innodb_online_alter_log_max_size") innodb_sort_buffer_size=$(SQL "SELECT @@innodb_sort_buffer_size") tmpdir=$(SQL "SELECT @@innodb_tmpdir")/$(SQL "SELECT @@tmpdir")"
} >> "$OUT/env.txt"

# ── [0] 원본 표 ───────────────────────────────────────────────────────────
want=$((SESSIONS * 600))
have=$(SQL "SELECT COUNT(*) FROM tpl_A" 2>/dev/null || echo 0)
if [ "${have:-0}" != "$want" ]; then
  log "[0] tpl_A 시딩 — 세션 $SESSIONS × 600 = $want 행"
  PY create tpl_A A >/dev/null || die "tpl_A 생성 실패"
  PY seed tpl_A "$SESSIONS" 600 | tee -a "$OUT/seed.jsonl" >&2 || die "시딩 실패"
  have=$(SQL "SELECT COUNT(*) FROM tpl_A")
  [ "$have" = "$want" ] || die "행 수가 $want 이 아니다: $have"
fi
log "[0] tpl_A $have 행 · PK=$(pk_cols tpl_A)"
PY stats tpl_A >> "$OUT/stats.jsonl"

# ── [1] 사전 확인 — INPLACE 를 받나 ───────────────────────────────────────
SQL "DROP TABLE IF EXISTS probe; CREATE TABLE probe LIKE tpl_A; INSERT INTO probe SELECT * FROM tpl_A LIMIT 1000;" \
  || die "probe 표 준비 실패"
if SQL "ALTER TABLE probe $NEWKEY, ALGORITHM=INPLACE, LOCK=NONE" ; then
  NATIVE="ALGORITHM=INPLACE, LOCK=NONE"; log "[1] ✅ 서버가 INPLACE·LOCK=NONE 을 받았다 — 팔 N 은 온라인 DDL 이다"
else
  NATIVE="ALGORITHM=COPY"; log "[1] 🔶 INPLACE 거절 — 팔 N 은 COPY(차단)다. 서버 응답: $(tail -1 "$OUT/mysql_stderr.log")"
fi
echo "native=$NATIVE probe_pk=$(pk_cols probe)" >> "$OUT/env.txt"
SQL "DROP TABLE probe"

# ── [2] 판 ────────────────────────────────────────────────────────────────
RAW="$OUT/raw.tsv"
[ -f "$RAW" ] || printf "tag\tarm\tddl_sec\tattempts\tok\terrors\tmax_attempt_ms\tmax_gap_ms\tbinlog_mb\tdisk_peak_mb\tpk_after\trows_after\n" > "$RAW"

for item in $SEQ; do
  tag=${item%%:*}; arm=${item##*:}
  log "[2] 판 $tag 팔 $arm — tpl_A 복사"
  SQL "DROP TABLE IF EXISTS t_rb; DROP TABLE IF EXISTS _t_rb_new; DROP TABLE IF EXISTS _t_rb_old;
       CREATE TABLE t_rb LIKE tpl_A; INSERT INTO t_rb SELECT * FROM tpl_A;" || die "복사 실패"
  SQL "PURGE BINARY LOGS BEFORE NOW()" ; SQL "FLUSH BINARY LOGS"
  b0=$(binlog_bytes)

  w="$HERE/_work/w_$tag.tsv"; rm -f "$w" "$w.stop"
  kill_writers
  if [ "$arm" != N0 ]; then
    docker exec -d natpk_client python /work/natpk.py writer t_rb "$WRITER_SECS" "$GAP_MS" "/work/out/w_$tag.tsv"
    # 2>/dev/null 을 < 앞에 — 뒤에 두면 파일이 아직 없을 때 리다이렉트 오류가 그대로 찍힌다(11-08 로그의 «No such file»)
    for _ in $(seq 1 30); do [ "$(wc -l 2>/dev/null < "$w" || echo 0)" -ge 3 ] && break; sleep 1; done
    [ "$(wc -l 2>/dev/null < "$w" || echo 0)" -ge 3 ] || die "writer 가 30초 안에 두 건을 못 썼다"
  fi

  ( peak=0; while [ ! -f "$OUT/.disk_stop" ]; do d=$(disk_mb); [ "${d:-0}" -gt "$peak" ] && peak=$d && echo "$peak" > "$OUT/.disk_peak"; sleep 5; done ) &
  dpid=$!
  t0=$(date +%s.%N)
  if [ "$arm" = N ] || [ "$arm" = N0 ]; then
    # SQL() 은 stderr 를 mysql_stderr.log 로 보낸다 — 11-08 라운드는 그래서 1799 가 *_ddl.log 에 안 남았다. DDL 은 직접 부른다
    docker exec -i natpk_mysql mysql -uroot -pnatpk natpk -N -s -e "ALTER TABLE t_rb $NEWKEY, $NATIVE" > "$OUT/${tag}_ddl.log" 2>&1; rc=$?
  else
    docker run --rm --network natpk_net "$PTOSC_IMAGE" pt-online-schema-change \
      --alter "$NEWKEY" --execute --no-check-alter --recursion-method=none --no-check-unique-key-change \
      "D=natpk,t=t_rb,h=natpk_mysql,u=root,p=natpk" > "$OUT/${tag}_ddl.log" 2>&1; rc=$?
  fi
  t1=$(date +%s.%N)
  sleep 3   # DDL 직후 정상 구간
  touch "$w.stop"; sleep 2
  touch "$OUT/.disk_stop"; wait $dpid 2>/dev/null; rm -f "$OUT/.disk_stop"
  peak=$(cat "$OUT/.disk_peak" 2>/dev/null); rm -f "$OUT/.disk_peak"
  b1=$(binlog_bytes)
  [ -f "$w" ] && cp "$w" "$OUT/"
  sec=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}')
  # 실패도 «몇 초 만에» 를 남긴다 — 11-08 은 1799 가 «DDL 이 끝나서» 가 아니라 «쓰기 로그가 차서» 났다
  [ $rc -eq 0 ] || { log "  ✗ DDL 실패 rc=$rc · ${sec}초 — $(grep -m1 ERROR "$OUT/${tag}_ddl.log")"; continue; }

  if [ "$arm" = N0 ]; then
    ws='{"attempts": "NA", "ok": "NA", "errors": "NA", "max_attempt_ms": "NA", "max_gap_ms": "NA"}'
  else
    ws=$(PY wsum "/work/out/w_$tag.tsv")
  fi
  pk=$(pk_cols t_rb); rows=$(SQL "SELECT COUNT(*) FROM t_rb")
  [ "$pk" = "session_id,rep_number,timestamp_sec,created_at" ] || log "  🔴 PK 가 안 바뀌었다: $pk"
  line=$(echo "$ws" | docker exec -i natpk_client python -c "import json,sys; j=json.load(sys.stdin); print('\t'.join(str(j[k]) for k in ['attempts','ok','errors','max_attempt_ms','max_gap_ms']))")
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$tag" "$arm" "$sec" "$line" "$(awk -v a="$b0" -v b="$b1" 'BEGIN{printf "%.0f",(b-a)/1048576}')" "${peak:-}" "$pk	$rows" >> "$RAW"
  log "  팔 $arm — DDL ${sec}초 · writer $ws · binlog +$(( (b1-b0)/1048576 ))MB · 디스크 최대 ${peak:-?}MB"
done

# ── [3] ③ 이 쓸 표 ────────────────────────────────────────────────────────
if [ "$(pk_cols t_rb)" = "session_id,rep_number,timestamp_sec,created_at" ]; then
  SQL "DROP TABLE IF EXISTS r_B; RENAME TABLE t_rb TO r_B;"
  log "[3] r_B 남김 — PK=$(pk_cols r_B)"
fi
kill_writers
log "완료 → $RAW"
