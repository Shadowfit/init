#!/usr/bin/env bash
# ① 삽입 처리량 — 팔 A(지금) · B(자연키 PK) · Bp(B − idx_session_timestamp)
#
# 설계: docs/decisions/r276-lock-root-cause-fix.md §5-4 ①
#
# 체제(REGIMES) × 동시 활성 세션(ACTIVES) 마다 팔 표를 새로 만들어 같은 무대를 깔고,
# 그 위에서 팔 × 블록을 라틴 방격으로 돈다(첫 블록 버림). 판마다 같은 행 수를 넣는다.
#
#   활성 세션 68 · 675 = 피크 동시 세션 가정(DAU × 0.0675 — ai-horizontal-scaling.md:35)의 DAU 1,000 · 10,000
#   일꾼 15 = Spring HikariCP maximum-pool-size(application.yml:74)
#
# 판마다 남기는 것: 행/초 · 묶음 p50/p99 · 버퍼풀 디스크 읽기 · 페이지 분할 · 판 뒤 표 크기(행당 바이트)
#
# 🔴 무대가 판마다 커진다(판이 TRIAL_ROWS 를 남긴다). 팔마다 표가 따로라 팔 사이엔 같은 만큼 커지고,
#    블록 사이의 표류는 원시 표의 «시작 행 수» 로 보이게 둔다(uk-bufferpool rig 과 같은 규약).
#
# 사용: bash phase1_insert.sh            (OUT=... 로 결과 위치, 나머지 손잡이는 아래 기본값)
set -uo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

REGIMES=${REGIMES:-"mem:4096 disk:64"}   # 이름:버퍼풀MB
ACTIVES=${ACTIVES:-"68 675"}
ARMS=${ARMS:-"A B Bp"}
BLOCKS=${BLOCKS:-4}                      # 첫 블록 버림
TRIAL_ROWS=${TRIAL_ROWS:-200000}
WORKERS=${WORKERS:-15}
SEED_SESSIONS=${SEED_SESSIONS:-2000}     # 무대 = 지난 세션 2,000개 × 600행 = 120만 행
OUT=${OUT:-$HERE/_out/phase1}
mkdir -p "$OUT" "$HERE/_work"

log(){ printf '[phase1 %s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log" >&2; }
die(){ log "🔴 중단 — $*"; exit 1; }
SQL(){ docker exec -i natpk_mysql mysql -uroot -pnatpk natpk -N -s -e "$1" 2>/dev/null; }
PY(){ docker exec -i natpk_client python /work/natpk.py "$@"; }
status(){ SQL "SHOW GLOBAL STATUS LIKE '$1'" | awk '{print $2}'; }
metric(){ SQL "SELECT count FROM information_schema.innodb_metrics WHERE name='$1'"; }

mysql_up(){ # $1 = 버퍼풀 MB
  log "MySQL 기동 — 버퍼풀 ${1}MB"
  POOL_MB=$1 docker compose up -d --force-recreate mysql >/dev/null 2>&1 || die "compose up 실패"
  local hits=0
  for _ in $(seq 1 120); do
    if SQL "SELECT 1" | grep -q 1; then hits=$((hits+1)); [ $hits -ge 2 ] && break; else hits=0; fi
    sleep 3
  done
  [ $hits -ge 2 ] || die "MySQL 이 6분 안에 준비되지 않았다"
  local got; got=$(SQL "SELECT @@innodb_buffer_pool_size")
  [ "$got" = "$(( $1 * 1024 * 1024 ))" ] || die "버퍼풀이 요청(${1}MB)과 다르다: $got"
  [ "$(SQL "SELECT status FROM information_schema.innodb_metrics WHERE name='index_page_splits'")" = "enabled" ] \
    || die "index_page_splits 카운터가 꺼져 있다"
}

log "클라이언트 기동"
docker compose up -d client >/dev/null 2>&1 || die "client 기동 실패"
for _ in $(seq 1 60); do docker exec natpk_client test -f /tmp/ready && break; sleep 2; done
docker exec natpk_client test -f /tmp/ready || die "client 준비 안 됨(pip)"

{
  echo "# start $(date '+%F %T')"
  echo "host_cpu=$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2) nproc=$(nproc 2>/dev/null)"
  echo "REGIMES=$REGIMES ACTIVES=$ACTIVES ARMS=$ARMS BLOCKS=$BLOCKS TRIAL_ROWS=$TRIAL_ROWS WORKERS=$WORKERS SEED_SESSIONS=$SEED_SESSIONS"
  echo "git=$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null)"
} > "$OUT/env.txt"

RAW="$OUT/raw.tsv"
[ -f "$RAW" ] || printf "regime\tpool_mb\tactive\tblock\tarm\trows_before\trows\tsec\trows_per_sec\tbatch_p50_ms\tbatch_p99_ms\terrors\tbp_reads\tbp_read_req\tpage_splits\tdata_len\tindex_len\tbytes_per_row\n" > "$RAW"

av=($ARMS); an=${#av[@]}
for rg in $REGIMES; do
  name=${rg%%:*}; pool=${rg##*:}
  mysql_up "$pool"
  echo "$name mysql=$(SQL "SELECT VERSION()") pool=$(SQL "SELECT @@innodb_buffer_pool_size")" >> "$OUT/env.txt"
  for act in $ACTIVES; do
    log "== 체제 $name(풀 ${pool}MB) · 활성 세션 $act — 팔 표 새로 깔기(세션 $SEED_SESSIONS 개)"
    for arm in $ARMS; do
      PY create "t_$arm" "$arm" >/dev/null || die "create $arm 실패"
      PY seed "t_$arm" "$SEED_SESSIONS" 600 >> "$OUT/seed.jsonl" || die "seed $arm 실패"
    done
    # 무대를 깐 뒤 버퍼풀 상태를 판마다 같게 — 재기동으로 비우고 시작한다
    mysql_up "$pool"
    for ((b=0;b<BLOCKS;b++)); do
      for ((k=0;k<an;k++)); do
        arm=${av[$(((k+b)%an))]}
        before=$(SQL "SELECT COUNT(*) FROM t_$arm")
        r0=$(status Innodb_buffer_pool_reads); q0=$(status Innodb_buffer_pool_read_requests); s0=$(metric index_page_splits)
        j=$(PY insert "t_$arm" "$act" "$TRIAL_ROWS" "$WORKERS") || die "insert $arm 실패"
        r1=$(status Innodb_buffer_pool_reads); q1=$(status Innodb_buffer_pool_read_requests); s1=$(metric index_page_splits)
        st=$(PY stats "t_$arm")
        echo "{\"regime\":\"$name\",\"active\":$act,\"block\":$b,\"arm\":\"$arm\",\"insert\":$j,\"stats\":$st}" >> "$OUT/trials.jsonl"
        line=$(PY fmt "$j" "$st")   # 호스트에 파이썬이 없어도 되게 클라이언트 컨테이너에서 편다
        IFS=$'\t' read -r rows sec rps p50 p99 err dl il bpr <<< "$line"
        printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
          "$name" "$pool" "$act" "$b" "$arm" "$before" "$rows" "$sec" "$rps" "$p50" "$p99" "$err" \
          "$((r1-r0))" "$((q1-q0))" "$((s1-s0))" "$dl" "$il" "$bpr" >> "$RAW"
        log "  블록 $b$([ $b = 0 ] && echo '(버림)') 팔 $arm — ${rps} 행/초 · p99 ${p99}ms · 디스크읽기 $((r1-r0)) · 분할 $((s1-s0)) · 오류 $err"
      done
    done
  done
done
log "완료 → $RAW"
