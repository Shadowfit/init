#!/usr/bin/env bash
# Spring→AI 왕복 — 동시성 축 (4차 라운드). 같은 두 팔(grpc · webclient)을 c 개 동시 재부착으로 잰다.
# 설계: docs/decisions/grpc-webclient-concurrency-round.md
#
# ## 2·3차와 무엇이 다른가
#
#   2·3차(measure_ai_call_latency_ab.sh): bash 가 사이클을 **순차**로 돌린다 — c=1 전용.
#   4차(이 파일): k6 의 VU c 개가 각자 세션 하나에 재부착을 **동시에** 반복한다. 재는 것은 여전히
#     Spring 안의 TimedAiAnalysisClient(shadowfit.ai.call)다. 거기에 «칸» 마다 컨테이너 CPU(cgroup
#     차분)와 처리량(재부착 ÷ 벽시계)을 같이 남긴다 — 서버(GIL)가 포화해 지연이 큐잉 지배가 돼도
#     CPU/재부착은 살아남는 지표라서다(설계 §3-1).
#
# ## 팔·c·판
#
#   팔 2 = grpc | webclient (풀 3, 배포 형상). 팔 전환 = 백엔드 재기동.
#   c 는 드라이버 인자라 재기동이 없다 → 블록 = (팔 하나, c 수준 전부). 팔 순서는 블록마다
#   반전(A→B / B→A), c 순서는 칸마다 한 칸씩 회전(§5-2).
#   칸 = (블록, 팔, c). VU c 개가 각각 세션 1개를 열고 재부착을 N 번 보낸다 → 칸당 표본 =
#   재부착 N × c, start·stop 은 c 건. (사이클 반복이 아닌 이유는 k6/ai_call_cycle.js 머리 주석.)
#
# ## 회수 (칸마다)
#
#   scrape/b{b}_{팔}_c{c}_{before|after}.txt   — shadowfit.ai.call 버킷(차분용)
#   k6/b{b}_{팔}_c{c}.json                      — 재부착 성공 건수·실패 계수
#   cells.tsv                                    — 벽시계·컨테이너 4개 CPU usec 전/후·서킷 상태
#   집계는 analyze_ai_call_concurrency.py.
#
# 사용:
#   BASE=http://localhost:8080 ACTUATOR=http://localhost:9090 \
#   COMPOSE_DIR=/opt/shadowfit N=100 BLOCKS=5 LEVELS="1 4 8 16 32" \
#   bash loadtest/measure_ai_call_concurrency.sh
#   로컬 스모크: N=6 BLOCKS=2 LEVELS="1 4" WARMUP=2 AI_CONC_MEM_LIMIT=2800m (워커당 풀 2)
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
ACTUATOR=${ACTUATOR:-http://localhost:9090}
COMPOSE_DIR=${COMPOSE_DIR:-$(pwd)}
N=${N:-100}                 # VU 당 재부착 수(칸당 표본 = N × c)
BLOCKS=${BLOCKS:-5}         # 유효 블록 수(팔당). 앞에 버림 블록 1개가 더 붙는다
WARMUP=${WARMUP:-10}        # 팔 전환 직후 버리는 재부착 수(c=1) · 버림 블록의 VU 당 재부착 수
LEVELS=${LEVELS:-"1 4 8 16 32"}   # 설계 §4-2 의 구조 문턱. 게이트 실측이 다르면 여기서 바꾼다
ARMS=${ARMS:-"grpc webclient"}
# 🔴 풀 3 = 배포 형상. 두 팔 다 session_id % 3 으로 같은 워커에 가므로 병렬도가 같다(§4-1).
CHANNEL_POOL_SIZE=${CHANNEL_POOL_SIZE:-3}
GRPC_MAX_WORKERS=${GRPC_MAX_WORKERS:-40}                  # §5-3 ① — anyio 기본 40 과 맞춤
OUTBOX_PUBLISHER_BATCH_SIZE=${OUTBOX_PUBLISHER_BATCH_SIZE:-200}   # §5-3 ③
AI_CONC_MEM_LIMIT=${AI_CONC_MEM_LIMIT:-6000m}             # §5-3 ④ — 검출기 풀 워커당 12 (오버레이 주석)
JWT_EXPIRATION_TIME=${JWT_EXPIRATION_TIME:-14400}         # §5-3 ⑤ — 라운드 2h > 기본 30분
K6_BIN=${K6_BIN:-k6}
PASSWORD=${PASSWORD:-'AbLatency!2026'}
PREP_SLEEP=${PREP_SLEEP:-1.3}   # 가입·로그인 IP당 60초 60건 상한 회피
PREFERRED_URL=${PREFERRED_URL:-https://www.youtube.com/watch?v=q6hBSSis_60}
EXERCISE_ID=${EXERCISE_ID:-1}
OUT=${OUT:-loadtest/results/ai-call-concurrency-$(date +%F)}

# 계정 = ROTATION 묶음 × 최대 c. 칸마다 계정 묶음을 교대한다 — 이웃 칸에서 같은 계정으로 다시
# start 하면 앞 칸 세션의 status 가 아직 IN_PROGRESS(콜백 전)라 409 가 난다(k6/ai_call_cycle.js
# 주석). 묶음 2개면 한 칸 걸러 재사용이라 그 사이에 배수 + 콜백이 끝난다. 모자라면 k6 가
# start_409 로 세고 분석기가 그 칸을 뺀다(규칙 3). 준비: 64개 × PREP_SLEEP 1.3s ≈ 1.5분.
ROTATION=${ROTATION:-2}
MAX_C=0; for c in $LEVELS; do [ "$c" -gt "$MAX_C" ] && MAX_C=$c; done
ACCOUNTS=${ACCOUNTS:-$(( ROTATION * MAX_C ))}
[ "$ACCOUNTS" -ge "$MAX_C" ] || { echo "🔴 ACCOUNTS=$ACCOUNTS < 최대 c=$MAX_C — VU 가 계정을 나눠 쓰게 된다"; exit 1; }

mkdir -p "$OUT/scrape" "$OUT/k6" || exit 1
exec > >(tee -a "$OUT/run.log") 2>&1
echo "# Spring→AI 동시성 축 — $(date -u +%FT%TZ)"
echo "BASE=$BASE N=$N BLOCKS=$BLOCKS WARMUP=$WARMUP LEVELS=[$LEVELS] ARMS=[$ARMS] POOL=$CHANNEL_POOL_SIZE"

command -v "$K6_BIN" >/dev/null 2>&1 || { echo "🔴 k6 가 없다($K6_BIN) — bootstrap ROLE=client-ab 가 설치한다"; exit 1; }

# ── 오버레이 — 서버 스레드 상한·아웃박스 배치를 라운드 동안만 바꾼다(§5-3 ①③) ──────────
# COMPOSE_FILE 로 얹는다: 이 셸에서 도는 모든 docker compose(팔 전환 포함)가 같은 조합을 본다.
# 🔴 AI 는 여기서 한 번 재기동한다 — GRPC_MAX_WORKERS 는 프로세스 기동 시 읽는 값이다.
export COMPOSE_FILE="docker-compose.yml:loadtest/aws/compose.clientconc.yml"
export COMPOSE_PATH_SEPARATOR=":"   # Windows compose 는 기본이 ';' 라 로컬 스모크에서 경로가 통째로 깨진다
export GRPC_MAX_WORKERS OUTBOX_PUBLISHER_BATCH_SIZE AI_CONC_MEM_LIMIT JWT_EXPIRATION_TIME
[ -f "$COMPOSE_DIR/loadtest/aws/compose.clientconc.yml" ] || { echo "🔴 오버레이가 없다: $COMPOSE_DIR/loadtest/aws/compose.clientconc.yml"; exit 1; }
echo "## AI 재기동(오버레이 적용: GRPC_MAX_WORKERS=$GRPC_MAX_WORKERS · mem_limit=$AI_CONC_MEM_LIMIT) ($(date -u +%T))"
( cd "$COMPOSE_DIR" && docker compose up -d --force-recreate shadowfit-ai ai-nginx ) >> "$OUT/compose.log" 2>&1 \
  || { echo "🔴 AI compose up 실패 — $OUT/compose.log 를 볼 것"; exit 1; }
curl -s -m 300 --retry 60 --retry-delay 3 --retry-all-errors -o /dev/null "http://localhost:8000/health" || true

# 팔 전환·계정 준비·사이클·배수·스크레이프·라틴 방격은 2·3차 rig 과 공용이다.
# shellcheck source=ai_call_ab_lib.sh
source "$(dirname "$0")/ai_call_ab_lib.sh"

# 팔 전환 + 이 라운드의 게이트 넷(아웃박스 배치·JWT 수명·AI 스레드 상한·AI 메모리 한도). 값이 안
# 실렸으면 멈춘다 — 조용히 기본값으로 돌면 c > 10 칸의 델타에 서버 설정 차이가 섞이거나(①),
# 32분째 칸부터 401 로 죽거나(⑤), c=4 부터 «풀 소진» 거절이 타이머에 성공으로 찍힌다(④).
CURRENT_ARM=""
switch_arm_conc() {
  switch_arm "$1"
  CURRENT_ARM=$1
  local got_batch got_jwt got_gw got_mem want_mem
  got_batch=$(backend_env OUTBOX_PUBLISHER_BATCH_SIZE)
  got_jwt=$(backend_env JWT_EXPIRATION_TIME)
  got_gw=$(ai_env GRPC_MAX_WORKERS)
  got_mem=$(docker inspect shadowfit-ai --format '{{.HostConfig.Memory}}' 2>/dev/null | tr -d '\r')
  want_mem=$(( ${AI_CONC_MEM_LIMIT%m} * 1024 * 1024 ))
  [ "$got_batch" = "$OUTBOX_PUBLISHER_BATCH_SIZE" ] || { echo "🔴 아웃박스 배치가 안 실렸다(got=$got_batch want=$OUTBOX_PUBLISHER_BATCH_SIZE) — 오버레이가 안 얹혔다, 중단"; exit 1; }
  [ "$got_jwt" = "$JWT_EXPIRATION_TIME" ] || { echo "🔴 JWT_EXPIRATION_TIME 이 안 실렸다(got=$got_jwt want=$JWT_EXPIRATION_TIME) — 중단"; exit 1; }
  [ "$got_gw" = "$GRPC_MAX_WORKERS" ] || { echo "🔴 GRPC_MAX_WORKERS 가 안 실렸다(got=$got_gw want=$GRPC_MAX_WORKERS) — 중단"; exit 1; }
  [ "$got_mem" = "$want_mem" ] || { echo "🔴 AI mem_limit 이 안 실렸다(got=$got_mem want=$want_mem) — 검출기 풀이 c 를 못 받는다, 중단"; exit 1; }
  # 유도되는 풀(워커당) — mediapipe_detector.py 의 공식 그대로. 최대 c 를 워커 3 이 나눠 받을 수 있어야 한다.
  local pool
  pool=$(awk -v lim="${AI_CONC_MEM_LIMIT%m}" -v w="$CHANNEL_POOL_SIZE" 'BEGIN { p = int((lim / w - 708) / 106.74); print (p < 1 ? 1 : p) }')
  echo "   OUTBOX_BATCH=$got_batch · JWT=${got_jwt}s · GRPC_MAX_WORKERS=$got_gw · AI mem=$AI_CONC_MEM_LIMIT → 검출기 풀 워커당 $pool (합 $(( pool * CHANNEL_POOL_SIZE )) ≥ 최대 c $MAX_C 여야 한다)"
  [ $(( pool * CHANNEL_POOL_SIZE )) -ge "$MAX_C" ] || { echo "🔴 검출기 풀 합 $(( pool * CHANNEL_POOL_SIZE )) < 최대 c $MAX_C — AI_CONC_MEM_LIMIT 을 올릴 것, 중단"; exit 1; }
}

# 🔴 계정(토큰)은 **오버레이가 얹힌 백엔드**에서 만들어야 한다 — 그 전의 백엔드는 JWT 수명이
#    기본 30분이라, 여기서 받은 토큰이 32분째 칸에서 만료된다(로컬 스모크에서 실제로 났다:
#    토큰의 exp = iat + 1800). 그래서 첫 팔로 한 번 재기동한 뒤에 계정을 만든다. 버림 블록의
#    첫 팔은 이미 떠 있으므로 다시 재기동하지 않는다.
read -r -a ARM_ARRAY <<< "$ARMS"
switch_arm_conc "${ARM_ARRAY[0]}"
prepare_accounts

# ── 컨테이너 CPU — cgroup 누적 usec. 칸 전/후 차분이 «그 칸의 CPU-초» 다 ────────────────
# k6 는 컨테이너 밖에서 돌므로 여기 안 섞인다(§5-1). v2(cpu.stat) 먼저, v1(cpuacct) 폴백.
# MSYS_NO_PATHCONV: Git Bash 가 /sys/fs/... 를 Windows 경로로 바꿔 버린다(로컬 스모크에서 전부 na 로 나왔다).
# 리눅스에선 아무 효과 없는 변수라 그대로 둔다.
cpu_usec() {  # $1=컨테이너
  local v
  v=$(MSYS_NO_PATHCONV=1 docker exec "$1" cat /sys/fs/cgroup/cpu.stat 2>/dev/null | awk '/^usage_usec/ {print $2}')
  if [ -z "$v" ]; then
    v=$(MSYS_NO_PATHCONV=1 docker exec "$1" cat /sys/fs/cgroup/cpu,cpuacct/cpuacct.usage 2>/dev/null | tr -d '[:space:]')
    [ -n "$v" ] && v=$(( v / 1000 ))
  fi
  echo "${v:-na}"
}
CONTAINERS="shadowfit-backend shadowfit-ai shadowfit-ai-nginx shadowfit-mysql"
cpu_row() { local c out=""; for c in $CONTAINERS; do out="$out	$(cpu_usec "$c")"; done; echo "$out"; }

# 서킷브레이커 상태 — 2차 블록 4 처럼 표본이 줄어든 칸을 사후에 설명하려면 이게 있어야 한다.
cb_state() {
  curl -s -m 30 "$ACTUATOR/actuator/prometheus" \
    | awk '/^resilience4j_circuitbreaker_state\{/ && $2 == 1 { match($0, /name="[^"]*"/); n=substr($0,RSTART+6,RLENGTH-7); match($0, /state="[^"]*"/); s=substr($0,RSTART+7,RLENGTH-8); printf "%s%s=%s", (k++ ? "," : ""), n, s }'
}

CELLS="$OUT/cells.tsv"
[ -f "$CELLS" ] || printf "block\tarm\tc\tslot\tstarted_at\twall_s\tk6_rc\tcpu_backend_before\tcpu_ai_before\tcpu_nginx_before\tcpu_mysql_before\tcpu_backend_after\tcpu_ai_after\tcpu_nginx_after\tcpu_mysql_after\tcb_before\tcb_after\n" > "$CELLS"

# k6 한 판 = 칸 하나. VU c 개가 각각 iters 사이클.
# 경로는 절대경로로 넘긴다 — k6 의 open() 은 스크립트 위치 기준이라 상대경로가 어긋난다.
# Git Bash 에서는 pwd -W 로 Windows 경로를 만든다(리눅스에선 -W 가 실패해 pwd 로 떨어진다).
abs_path() { echo "$(cd "$(dirname "$1")" && { pwd -W 2>/dev/null || pwd; })/$(basename "$1")"; }
K6_SCRIPT=$(abs_path "$(dirname "$0")/k6/ai_call_cycle.js")

# 칸 일련번호 — k6 가 계정 묶음을 교대하는 키. 버림·워밍업 판도 세션을 열므로 같이 센다.
CELL_SEQ=0
run_k6() {  # $1=c $2=VU당 재부착 수 $3=요약 JSON 경로('' 이면 안 남김)
  local c=$1 iters=$2 summary=$3
  [ -n "$summary" ] && summary=$(abs_path "$summary")
  CELL_SEQ=$((CELL_SEQ+1))
  BASE="$BASE" VUS="$c" ITERS="$iters" EXERCISE_ID="$EXERCISE_ID" TOKENS_FILE="$(abs_path "$TOKENS")" SUMMARY="$summary" \
  CELL_SEQ="$CELL_SEQ" MAX_C="$MAX_C" \
    "$K6_BIN" run --quiet --no-usage-report "$K6_SCRIPT" 2>&1 | grep -v '^$' | sed 's/^/     k6| /'
  return "${PIPESTATUS[0]}"
}

# 칸 하나 — 전 회수 → k6 → 배수 → 후 회수 → cells.tsv 한 줄.
run_cell() {  # $1=블록 $2=팔 $3=c $4=회전 자리
  local b=$1 arm=$2 c=$3 slot=$4
  local tag="b${b}_${arm}_c${c}"
  local cpu_b cpu_a cb_b cb_a t0 t1 rc started wall
  scrape "${tag}_before"
  cpu_b=$(cpu_row); cb_b=$(cb_state)
  started=$(date -u +%FT%TZ); t0=$(date +%s.%N)
  run_k6 "$c" "$N" "$OUT/k6/${tag}.json"; rc=$?
  t1=$(date +%s.%N)
  # 🔴 칸 경계마다 배수 — 앞 칸의 stop 이 다음 칸의 AI 부하에 섞이지 않게. 벽시계엔 안 넣는다.
  drain "$tag" || true
  scrape "${tag}_after"
  cpu_a=$(cpu_row); cb_a=$(cb_state)
  wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN { printf "%.3f", b - a }')   # bc 는 박스에 없을 수 있다
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s%s%s\t%s\t%s\n" "$b" "$arm" "$c" "$slot" "$started" \
    "$wall" "$rc" "$cpu_b" "$cpu_a" "${cb_b:-none}" "${cb_a:-none}" >> "$CELLS"
  echo "   ✅ 칸 $tag 회수 — 벽시계 ${wall}s · k6 rc=$rc"
}

# ── 구조 확인 게이트 — 설계 §2 의 «기본값» 을 실측한다(§5-4). c 수준의 뜻이 여기 걸려 있다 ──
structure_gate() {  # $1=팔 — 팔마다 스레드 구성이 다르므로 파일을 따로 남긴다
  local f="$OUT/structure_$1.txt"
  {
    echo "# 구조 확인 게이트 ($1) $(date -u +%FT%TZ)"
    echo "## JVM 이 보는 코어 수 (system_cpu_count — /actuator/metrics 는 401 이라 prometheus 에서 읽는다)"
    curl -s -m 10 "$ACTUATOR/actuator/prometheus" | grep '^system_cpu_count' || echo "na"
    echo "## 백엔드 스레드 이름별 개수 (/proc/*/task/*/comm — 15자 절단)"
    docker exec shadowfit-backend sh -c 'cat /proc/*/task/*/comm 2>/dev/null | sort | uniq -c | sort -rn' | head -40
    echo "## 기대(§2): reactor-http-epoll|nio = max(cores,4) · grpc-default-worker-ELG = cores×2(🟡 확인 대상)"
    echo "## 🔴 comm 은 15자 절단이다 — reactor-http-epoll-N 은 «reactor-http-ep», grpc-default-worker-ELG-N 은 «grpc-default-wo» 로 보인다"
    echo "## AI 컨테이너: GRPC_MAX_WORKERS=$(ai_env GRPC_MAX_WORKERS) AI_WORKER_COUNT=$(ai_env AI_WORKER_COUNT)"
    echo "## 백엔드: AI_CHANNEL_POOL_SIZE=$(backend_env AI_CHANNEL_POOL_SIZE) OUTBOX_PUBLISHER_BATCH_SIZE=$(backend_env OUTBOX_PUBLISHER_BATCH_SIZE)"
  } > "$f" 2>&1
  echo "## 구조 게이트 회수 — $f"
  # 🔴 리눅스 Reactor Netty 는 epoll 이라 스레드 이름이 reactor-http-epoll-N 이다(nio 아님). 15자 절단까지
  #    감안해 접두어로 센다. ELG 는 게으르게 생겨서 «지금까지 쓰인 수» 이지 상한이 아니다 — 큰 c 를 밟은
  #    버림 블록 뒤에 세는 이유가 그것이다.
  local loops elg
  loops=$(docker exec shadowfit-backend sh -c 'cat /proc/*/task/*/comm 2>/dev/null' | grep -c '^reactor-http-' || true)
  elg=$(docker exec shadowfit-backend sh -c 'cat /proc/*/task/*/comm 2>/dev/null' | grep -c '^grpc-default-wo' || true)
  echo "   [$1] reactor-http-* 이벤트루프 $loops 개 · grpc-default-worker-ELG $elg 개 · JVM cores $(curl -s -m 10 "$ACTUATOR/actuator/prometheus" | awk '/^system_cpu_count/ {print $2}') (c 수준 [$LEVELS] 이 이 문턱을 끼우는지 볼 것)"
}

# ── 라운드 ───────────────────────────────────────────────────────────────
read -r -a LEVEL_ARRAY <<< "$LEVELS"
read -r -a ARM_ARRAY <<< "$ARMS"
NL=${#LEVEL_ARRAY[@]}

# 버림 블록: 팔마다 c 수준 전부를 짧게(VU 당 WARMUP 재부착) 밟는다 — JIT·풀·커넥션이 «큰 c» 도
# 한 번은 겪게. 기록엔 안 넣는다. 구조 게이트는 두 팔이 다 한 번씩 뜬 뒤에 찍는다.
for arm in $ARMS; do
  [ "$CURRENT_ARM" = "$arm" ] || switch_arm_conc "$arm"
  for c in $LEVELS; do
    run_k6 "$c" "$WARMUP" "" >/dev/null; echo "   버림 $arm c=$c"
  done
  drain "discard/$arm" || true
  # 이벤트루프·ELG 스레드는 첫 호출 뒤에야 생긴다 — 버림 뒤에 센다. 팔마다 스레드 구성이
  # 다르므로(grpc 팔엔 reactor 루프가 안 뜰 수 있다) 둘 다 남긴다.
  structure_gate "$arm"
done

slot=0
for b in $(seq 1 "$BLOCKS"); do
  order=$(rotate $(( (b - 1) % ${#ARM_ARRAY[@]} )) "${ARM_ARRAY[@]}")
  echo "## 블록 $b 팔 순서: $order"
  for arm in $order; do
    switch_arm_conc "$arm"
    # 팔 전환 직후 워밍업(c=1) → 배수 → 본판. 워밍업 잔여가 첫 칸에 섞이지 않게.
    run_k6 1 "$WARMUP" "" >/dev/null
    drain "warmup b$b/$arm" || true
    # c 순서는 칸(팔)마다 한 칸씩 회전 — «큰 c 뒤의 작은 c» 효과가 특정 c 에만 붙지 않게(§5-2).
    lorder=$(rotate $(( slot % NL )) "${LEVEL_ARRAY[@]}")
    echo "## 블록 $b $arm c 순서: $lorder"
    for c in $lorder; do
      run_cell "$b" "$arm" "$c" "$slot"
    done
    slot=$((slot+1))
  done
done

docker logs shadowfit-ai-nginx > "$OUT/nginx-access.log" 2>&1 || echo "   ⚠️ nginx 로그 회수 실패"

echo "## 완료 $(date -u +%FT%TZ) — 칸 $(( $(wc -l < "$CELLS") - 1 )) 개 · 스크레이프 $(ls "$OUT/scrape" | wc -l) 개"
echo "   집계: python loadtest/analyze_ai_call_concurrency.py $OUT"
