#!/usr/bin/env bash
# Spring→AI 왕복 지연 A/B — 프로덕션 클라이언트(grpc-java vs Spring WebClient)로 잰다.
# 설계: docs/decisions/grpc-webclient-production-client-round.md
#
# ## 1차 라운드와 무엇이 다른가
#
#   1차(measure_grpc_vs_webclient.sh): ghz/k6 가 AI 를 **직접** 친다 — 재는 클라이언트가
#     ghz(Go)·k6 라서, 84KB 페이로드에서 도구를 바꾸니 델타 부호가 뒤집혔다(§10.2).
#   2차(이 파일): **Spring 안의 클라이언트**가 보낸 왕복을 Spring 이 스스로 잰다
#     (shadowfit.ai.call 타이머, TimedAiAnalysisClient). 드라이버는 그 호출을 촉발할 뿐이라
#     드라이버↔Spring 구간은 측정 밖이다.
#
# ## 팔과 판
#
#   팔 2개 = AI_CLIENT_TYPE=grpc | webclient. 스타트업 프로퍼티라 팔 전환 = 백엔드 재기동.
#   블록 = 한 팔로 N 사이클(사이클 = 세션 시작 → 재부착(큰 요청) → 종료(작은 요청)).
#   버림 블록 1개 + 유효 블록은 팔 순서를 뒤집어 반복한다(A→B / B→A).
#
# ## 회수
#
#   블록 시작/끝에 /actuator/prometheus 를 긁어 저장한다. 판정은 **버킷 차분**으로 한다 —
#   타이머가 누적이라 차분하지 않으면 블록이 안 갈린다. 집계는 analyze_ai_call_latency_ab.py.
#
# 사용:
#   BASE=http://localhost:8080 ACTUATOR=http://localhost:9090 \
#   COMPOSE_DIR=/opt/shadowfit N=100 BLOCKS=5 bash loadtest/measure_ai_call_latency_ab.sh
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
ACTUATOR=${ACTUATOR:-http://localhost:9090}
COMPOSE_DIR=${COMPOSE_DIR:-$(pwd)}
N=${N:-100}                 # 블록당 사이클 수
BLOCKS=${BLOCKS:-5}         # 유효 블록 수(팔당). 앞에 버림 블록 1개가 더 붙는다
WARMUP=${WARMUP:-10}        # 팔 전환 직후 버리는 사이클(JIT·풀·커넥션)
ACCOUNTS=${ACCOUNTS:-${N}}
# 3차 라운드 기본은 팔 셋. 2차처럼 두 팔만 돌리려면 ARMS="grpc webclient-nginx".
ARMS=${ARMS:-"grpc webclient-nginx webclient-direct"}
# 🔴 전 팔 워커 0 고정. 풀이 3이면 gRPC 는 session_id%3 으로 흩어지고 REST 팔은 헤더대로
#    가는데, 그러면 프로토콜이 아니라 «병렬도» 를 재게 된다(1차 rig 이 쓴 것과 같은 장치).
#    풀 ≤ 워커 수여야 안전하다 — 1 은 항상 안전하다.
CHANNEL_POOL_SIZE=${CHANNEL_POOL_SIZE:-1}
PASSWORD=${PASSWORD:-'AbLatency!2026'}
PREP_SLEEP=${PREP_SLEEP:-1.3}   # 가입·로그인 IP당 60초 60건 상한 회피
PREFERRED_URL=${PREFERRED_URL:-https://www.youtube.com/watch?v=q6hBSSis_60}
EXERCISE_ID=${EXERCISE_ID:-1}
OUT=${OUT:-loadtest/results/ai-call-latency-ab-$(date +%F)}

mkdir -p "$OUT/scrape" || exit 1
exec > >(tee -a "$OUT/run.log") 2>&1
echo "# Spring→AI 왕복 지연 A/B — $(date -u +%FT%TZ)"
echo "BASE=$BASE N=$N BLOCKS=$BLOCKS WARMUP=$WARMUP"

# 팔 전환·계정 준비·사이클·배수·스크레이프·라틴 방격은 4차 rig 과 공용이다.
# shellcheck source=ai_call_ab_lib.sh
source "$(dirname "$0")/ai_call_ab_lib.sh"

prepare_accounts

run_block() {
  local arm=$1 label=$2 count=$3
  local i=0
  while [ "$i" -lt "$count" ]; do
    cycle "${POOL[$(( i % READY ))]}" >/dev/null
    i=$((i+1))
  done
  echo "   블록 $label($arm) 사이클 $count 완료 $(date -u +%T)"
}

# ── 라운드 ───────────────────────────────────────────────────────────────
# 버림 블록: 팔마다 한 번씩 돌리고 기록에 안 넣는다.
for arm in $ARMS; do
  switch_arm "$arm"
  run_block "$arm" "discard" "$WARMUP"
  drain "discard/$arm" || true
done

read -r -a ARM_ARRAY <<< "$ARMS"
for b in $(seq 1 "$BLOCKS"); do
  order=$(rotate $(( (b - 1) % ${#ARM_ARRAY[@]} )) "${ARM_ARRAY[@]}")
  echo "## 블록 $b 순서: $order"
  for arm in $order; do
    switch_arm "$arm"
    run_block "$arm" "warmup" "$WARMUP"
    # 워밍업의 stop 도 배수하고 나서 기준선을 찍는다 — 안 그러면 워밍업 잔여가 본 블록에 섞인다.
    drain "warmup b$b/$arm" || true
    scrape "b${b}_${arm}_before"
    run_block "$arm" "b$b" "$N"
    drain "b$b/$arm" || true
    scrape "b${b}_${arm}_after"
    echo "   ✅ 블록 $b/$BLOCKS ($arm) 회수 완료"
  done
done

# nginx 액세스 로그 회수 — 커넥션 재사용률($connection 별 요청 수)과 전선 위 실제 요청
# 바이트($request_length)가 여기 있다. 팔 C(직결)는 nginx 를 안 거치므로 그 팔의 줄은 없다.
docker logs shadowfit-ai-nginx > "$OUT/nginx-access.log" 2>&1 || echo "   ⚠️ nginx 로그 회수 실패"

echo "## 완료 $(date -u +%FT%TZ) — 스크레이프 $(ls "$OUT/scrape" | wc -l) 개"
echo "   집계: python loadtest/analyze_ai_call_latency_ab.py $OUT"
