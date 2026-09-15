#!/usr/bin/env bash
# AI 워커 부하-중 자발적 장애 빈도 — 부하기 쪽(계정 준비 + 세션 시작/종료 루프).
# 설계: docs/decisions/ai-worker-load-soak-experiment.md
#
# 🔴 세션 시작(POST /exercises/sessions)만 반복한다 — 프레임 스트리밍(POST /pose)은 안 한다
#    (§4-1, 2026-08-28 사용자 확정). AI 채널 풀에 목표 동접만큼 "빈 세션"을 유지하는 것이지
#    실제 MediaPipe 추론 부하를 거는 게 아니다. "장애 0회"가 나와도 그건 이 좁은 조건의 결론이다.
#
# 대상 박스의 컨테이너 상태(RestartCount·OOMKilled 등)는 이 스크립트가 아니라
# measure_ai_worker_load_soak_monitor.sh 가 대상 박스에서 따로 걷는다.
set -uo pipefail

BASE=${BASE:?BASE(대상 URL) 필요 — 예: http://<대상-사설IP>:8080}
ACCOUNTS=${ACCOUNTS:-203}            # 동접 배수 3배 목표(202.5 → 반올림). §5
HOLD_SEC=${HOLD_SEC:-900}            # 세션 유지 시간 — load-test-strategy.md §4.2 앵커(15분)
DURATION_SEC=${DURATION_SEC:-10800}  # 3시간 기본(2~4시간 범위의 중간값, §5)
PASSWORD=${K6_PASSWORD:-'AiSoak!2026'}
PREFERRED_URL=${PREFERRED_URL:-https://www.youtube.com/watch?v=aisoakrig}
PREP_SLEEP=${PREP_SLEEP:-2.5}        # 가입·로그인 IP당 60초 60건 상한 — 분당 48건으로 유지
EXERCISE_ID=${EXERCISE_ID:-1}
PREFIX=${ACCOUNT_PREFIX:-aisoak}
OUT=${OUT:-loadtest/results/ai-worker-load-soak-$(date +%F)}

mkdir -p "$OUT/workers" || exit 1

echo "# AI 워커 부하-중 장애 빈도 — $(date -u +%FT%TZ)"
echo "대상: $BASE"
echo "계정 목표: $ACCOUNTS · hold=${HOLD_SEC}s · duration=${DURATION_SEC}s"
echo

# ── 계정 준비 ────────────────────────────────────────────────────────────
# measure_http_write_p99.sh [1-b] 블록과 같은 절차: signup → login → onboarding(preferredUrl).
EMAILS="$OUT/emails.txt"; TOKENS="$OUT/tokens.txt"
: > "$EMAILS"; : > "$TOKENS"
echo "## 계정 준비 ($ACCOUNTS 개, ${PREP_SLEEP}s 간격 — 레이트리밋 60/60s 보호)"
for i in $(seq 1 "$ACCOUNTS"); do
  email="${PREFIX}${i}_$(date +%s)@test.local"
  curl -s -o /dev/null -m 30 -X POST "$BASE/member/signup" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${PREFIX}${i}\",\"email\":\"$email\",\"password\":\"$PASSWORD\",\"sex\":\"MALE\",\"role\":\"USER\"}"
  tok=$(curl -s -m 30 -X POST "$BASE/member/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [ -z "$tok" ]; then
    echo "  🔴 $email 로그인 실패 — 워커 풀에서 뺀다(429면 PREP_SLEEP을 올릴 것)"
    continue
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 30 -X PATCH "$BASE/member/onboarding/$email" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $tok" \
    -d "{\"preferredUrl\":\"$PREFERRED_URL\"}")
  if [ "$code" != "200" ]; then
    echo "  🔴 $email 온보딩 실패($code) — 워커 풀에서 뺀다"
    continue
  fi
  echo "$email" >> "$EMAILS"
  echo "$tok" >> "$TOKENS"
  sleep "$PREP_SLEEP"
done
N_READY=$(wc -l < "$TOKENS" | tr -d '[:space:]')
echo "  ✅ 계정 $N_READY / $ACCOUNTS 준비됨"
[ "$N_READY" -ge 1 ] || { echo "🔴 준비된 계정이 0개 — 중단"; exit 1; }

# ── 토큰 수명 게이트 (#689) ───────────────────────────────────────────────
# 이 rig 은 준비 단계에서 받은 accessToken 을 루프 내내 **재발급 없이** 쓴다. access TTL 이
# 기본 1800초(30분)이므로 DURATION_SEC 가 그보다 길면 판 도중에 전부 401 이 되고, 워커는
# START_FAIL 을 찍으며 5초마다 재시도만 한다 — **부하가 사라졌는데 판정 채널은 «장애 0회»를
# 찍는다.** 08-28 라운드가 90초·10분 만에 죽어서 이 자리를 한 번도 안 밟아 봤다.
# 여기서 «조용히 무부하» 대신 «시작 전에 중단» 으로 바꾼다.
TOKEN_MARGIN_SEC=${TOKEN_MARGIN_SEC:-600}   # 모니터의 TAIL_SEC 기본값 — 꼬리 관찰 구간까지 살아 있어야 한다
b64url_decode() {
  local s="$1"
  while [ $(( ${#s} % 4 )) -ne 0 ]; do s="${s}="; done
  echo "$s" | tr '_-' '/+' | base64 -d 2>/dev/null
}
# 🔴 콜론 뒤 공백을 허용한다 — 발급기가 바뀌어 `"exp": 123` 으로 나오면 파싱이 조용히
#    실패하고, 게이트가 «토큰이 짧다» 가 아니라 «못 읽었다» 로 엉뚱하게 막는다(로컬 검증에서 잡음).
TOK_EXP=$(b64url_decode "$(head -1 "$TOKENS" | cut -d. -f2)"   | grep -oE '"exp"[[:space:]]*:[[:space:]]*[0-9]+' | grep -oE '[0-9]+')
if [ -z "$TOK_EXP" ]; then
  echo "🔴 토큰의 exp 를 못 읽었다 — 게이트를 통과시킬 수 없다(#689). base64/JWT 형식을 확인할 것"; exit 1
fi
TOK_REMAIN=$(( TOK_EXP - $(date +%s) ))
TOK_NEED=$(( DURATION_SEC + TOKEN_MARGIN_SEC ))
echo "  토큰 수명: 남은 ${TOK_REMAIN}s / 필요 ${TOK_NEED}s (= DURATION_SEC ${DURATION_SEC} + 여유 ${TOKEN_MARGIN_SEC})"
if [ "$TOK_REMAIN" -lt "$TOK_NEED" ]; then
  cat <<GATE
🔴 토큰이 판 전체를 못 덮는다 — 중단한다 (#689)
   남은 ${TOK_REMAIN}s < 필요 ${TOK_NEED}s
   그대로 돌리면 약 ${TOK_REMAIN}s 뒤 워커 전부가 401 을 받고, 남은 구간은 부하가 없는데도
   판정 채널이 「장애 0회」를 찍는다. 결과가 안 나오는 게 아니라 **틀린 결과가 나온다.**
   처방: 대상 박스 .env 에 JWT_EXPIRATION_TIME 을 올리고 백엔드를 다시 띄운다
         (하한 = 계정 준비 시간 + DURATION_SEC + TAIL_SEC · 라운드 매니페스트 §4-ㅁ).
         네 팔 전부 같은 값을 써야 토큰 수명이 새 교락 변수가 되지 않는다.
GATE
  exit 1
fi

# 준비의 마지막 로그인과 워커 루프의 첫 요청이 같은 레이트리밋 창에 들지 않게 비운다.
echo "  창 비우기 60초"
sleep 60

# ── 워커 루프 ────────────────────────────────────────────────────────────
# 고정 크기 워커 풀 — 각 워커가 세션 하나를 끝내야 다음을 시작하므로, 동접이 자동으로
# 계정 수(N_READY) 근처로 유지된다(정교한 레이트 제어 불필요, §4 결정 로그).
DEADLINE=$(( $(date +%s) + DURATION_SEC ))
echo "## 부하 시작 — 워커 $N_READY 개, 종료 예정 epoch=$DEADLINE"

worker() {
  local idx=$1 tok=$2 log="$OUT/workers/w${idx}.log"
  : > "$log"
  local started=0 ended=0 failed=0
  while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    resp=$(curl -s -m 15 -X POST "$BASE/exercises/sessions" \
      -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
      -d "{\"exerciseId\":$EXERCISE_ID}")
    sid=$(echo "$resp" | grep -oE '"sessionId":[0-9]+' | grep -oE '[0-9]+')
    if [ -z "$sid" ]; then
      failed=$((failed+1))
      echo "$(date +%s) START_FAIL resp=$resp" >> "$log"
      sleep 5
      continue
    fi
    started=$((started+1))
    echo "$(date +%s) START sid=$sid" >> "$log"

    now=$(date +%s)
    remain=$(( DEADLINE - now ))
    sleep_for=$HOLD_SEC
    [ "$remain" -lt "$sleep_for" ] && sleep_for=$remain
    [ "$sleep_for" -lt 0 ] && sleep_for=0
    sleep "$sleep_for"

    code=$(curl -s -o /dev/null -w '%{http_code}' -m 15 -X PATCH "$BASE/sessions/$sid/end" \
      -H "Authorization: Bearer $tok")
    ended=$((ended+1))
    echo "$(date +%s) END sid=$sid code=$code" >> "$log"
  done
  echo "$(date +%s) DONE started=$started ended=$ended failed=$failed" >> "$log"
}

mapfile -t POOL_TOKENS < "$TOKENS"
idx=0
for tok in "${POOL_TOKENS[@]}"; do
  idx=$((idx+1))
  worker "$idx" "$tok" &
  sleep 0.05   # 203개가 같은 밀리초에 뭉치지 않게 하는 지터 — 「부하」가 아니라 기동 스팸 완화
done

echo "  워커 $idx 개 기동 (백그라운드) — nohup 으로 띄웠다면 SSH 를 끊어도 계속 산다"
wait
echo "## 완료 — $(date -u +%FT%TZ)"
echo "  워커별 로그: $OUT/workers/w*.log (started/ended/failed 요약은 각 로그 마지막 줄)"
