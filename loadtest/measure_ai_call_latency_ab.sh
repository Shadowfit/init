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
PASSWORD=${PASSWORD:-'AbLatency!2026'}
PREP_SLEEP=${PREP_SLEEP:-1.3}   # 가입·로그인 IP당 60초 60건 상한 회피
PREFERRED_URL=${PREFERRED_URL:-https://www.youtube.com/watch?v=q6hBSSis_60}
EXERCISE_ID=${EXERCISE_ID:-1}
OUT=${OUT:-loadtest/results/ai-call-latency-ab-$(date +%F)}

mkdir -p "$OUT/scrape" || exit 1
exec > >(tee -a "$OUT/run.log") 2>&1
echo "# Spring→AI 왕복 지연 A/B — $(date -u +%FT%TZ)"
echo "BASE=$BASE N=$N BLOCKS=$BLOCKS WARMUP=$WARMUP"

# ── 팔 전환 ──────────────────────────────────────────────────────────────
switch_arm() {
  local arm=$1
  echo "## 팔 전환 → $arm ($(date -u +%T))"
  ( cd "$COMPOSE_DIR" && AI_CLIENT_TYPE="$arm" docker compose up -d --force-recreate shadowfit-backend >/dev/null 2>&1 )
  # 헬스가 UP 이 될 때까지. curl 자체 재시도라 sleep 루프를 안 쓴다.
  curl -s -m 300 --retry 100 --retry-delay 3 --retry-all-errors -o /dev/null "$ACTUATOR/actuator/health" || true
  local got
  got=$(docker exec shadowfit-backend printenv AI_CLIENT_TYPE 2>/dev/null | tr -d '\r')
  if [ "$got" != "$arm" ]; then
    echo "🔴 팔이 안 바뀌었다(got=$got, want=$arm) — 중단"; exit 1
  fi
  # 🔴 «어느 구현체가 실제로 떴나» 를 지표로 확인한다. 프로퍼티만 보면 조립 실패를 못 잡는다.
  echo "   AI_CLIENT_TYPE=$got"
}

# ── 계정 준비 (측정 대상 아님 — 레이트리밋 아래로 페이싱) ────────────────
TOKENS="$OUT/tokens.txt"; EMAILS="$OUT/emails.txt"
if [ ! -s "$TOKENS" ]; then
  : > "$TOKENS"; : > "$EMAILS"
  echo "## 계정 준비 $ACCOUNTS 개 (간격 ${PREP_SLEEP}s)"
  for i in $(seq 1 "$ACCOUNTS"); do
    email="ablat${i}_$(date +%s)@test.local"
    curl -s -o /dev/null -m 30 -X POST "$BASE/member/signup" -H 'Content-Type: application/json' \
      -d "{\"username\":\"ablat$i\",\"email\":\"$email\",\"password\":\"$PASSWORD\",\"sex\":\"MALE\",\"role\":\"USER\"}"
    tok=$(curl -s -m 30 -X POST "$BASE/member/login" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
    [ -n "$tok" ] || { echo "  🔴 $email 로그인 실패 — 건너뜀"; sleep "$PREP_SLEEP"; continue; }
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 30 -X PATCH "$BASE/member/onboarding/$email" \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $tok" \
      -d "{\"selectedPersona\":\"BEGINNER\",\"workoutLevel\":\"BEGINNER\",\"height\":175.0,\"weight\":70.0,\"preferredUrl\":\"$PREFERRED_URL\"}")
    [ "$code" = "200" ] || { echo "  🔴 $email 온보딩 $code — 건너뜀"; sleep "$PREP_SLEEP"; continue; }
    echo "$email" >> "$EMAILS"; echo "$tok" >> "$TOKENS"
    sleep "$PREP_SLEEP"
  done
fi
READY=$(wc -l < "$TOKENS" | tr -d '[:space:]')
echo "  ✅ 계정 $READY 개"
[ "$READY" -ge 1 ] || { echo "🔴 계정 0개 — 중단"; exit 1; }
mapfile -t POOL < "$TOKENS"

# ── 사이클 하나 = 세션 시작 → 재부착(큰 요청) → 종료(작은 요청) ─────────
cycle() {
  local tok=$1
  local sid
  sid=$(curl -s -m 30 -X POST "$BASE/exercises/sessions" -H "Authorization: Bearer $tok" \
        -H 'Content-Type: application/json' -d "{\"exerciseId\":$EXERCISE_ID}" \
        | grep -oE '"sessionId":[0-9]+' | grep -oE '[0-9]+')
  [ -n "$sid" ] || { echo "  START_FAIL"; return 1; }
  # 재부착 — reference_poses 가 실려 나가는 «큰 요청» 팔.
  curl -s -o /dev/null -m 30 -X POST "$BASE/sessions/$sid/reattach" -H "Authorization: Bearer $tok"
  # 종료 — 아웃박스 발행기가 StopAnalysis(작은 요청)를 보낸다.
  curl -s -o /dev/null -m 30 -X PATCH "$BASE/sessions/$sid/end" -H "Authorization: Bearer $tok"
  echo "$sid"
}

run_block() {
  local arm=$1 label=$2 count=$3
  local i=0
  while [ "$i" -lt "$count" ]; do
    cycle "${POOL[$(( i % READY ))]}" >/dev/null
    i=$((i+1))
  done
  echo "   블록 $label($arm) 사이클 $count 완료 $(date -u +%T)"
}

scrape() { curl -s -m 30 "$ACTUATOR/actuator/prometheus" | grep -E '^shadowfit_ai_call_seconds' > "$OUT/scrape/$1.txt"; }

# ── 라운드 ───────────────────────────────────────────────────────────────
# 버림 블록: 팔마다 한 번씩 돌리고 기록에 안 넣는다.
for arm in grpc webclient; do
  switch_arm "$arm"
  run_block "$arm" "discard" "$WARMUP"
done

for b in $(seq 1 "$BLOCKS"); do
  # 순서 반전 — 홀수 블록은 grpc 먼저, 짝수 블록은 webclient 먼저.
  if [ $(( b % 2 )) -eq 1 ]; then order="grpc webclient"; else order="webclient grpc"; fi
  for arm in $order; do
    switch_arm "$arm"
    run_block "$arm" "warmup" "$WARMUP"
    scrape "b${b}_${arm}_before"
    run_block "$arm" "b$b" "$N"
    # 아웃박스 발행기가 StopAnalysis 를 비동기로 보내므로 잠깐 배수한다.
    curl -s -m 30 -o /dev/null "$ACTUATOR/actuator/health"
    sleep 10
    scrape "b${b}_${arm}_after"
    echo "   ✅ 블록 $b/$BLOCKS ($arm) 회수 완료"
  done
done

echo "## 완료 $(date -u +%FT%TZ) — 스크레이프 $(ls "$OUT/scrape" | wc -l) 개"
echo "   집계: python loadtest/analyze_ai_call_latency_ab.py $OUT"
