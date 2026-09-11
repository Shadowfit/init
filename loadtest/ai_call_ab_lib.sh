#!/usr/bin/env bash
# Spring→AI 왕복 A/B rig 들의 공용 함수 — 2차·3차(measure_ai_call_latency_ab.sh)와
# 4차(measure_ai_call_concurrency.sh)가 같은 팔 전환·계정 준비·배수·스크레이프를 쓴다.
#
# 🔴 여기 있는 것은 전부 «2차 로컬 스모크가 잡은 결함» 을 박아둔 자리다 — 조용한 팔전환 실패,
#    닉네임 중복, 배수 없는 팔 전환. 한 rig 에만 고치고 다른 rig 에 안 옮기는 일이 없도록
#    파일을 하나로 뒀다. 함수는 호출자의 변수(BASE·ACTUATOR·COMPOSE_DIR·OUT·CHANNEL_POOL_SIZE·
#    PASSWORD·PREP_SLEEP·PREFERRED_URL·EXERCISE_ID·ACCOUNTS)를 그대로 읽는다.
#
# 사용:  source "$(dirname "$0")/ai_call_ab_lib.sh"

# ── 팔 → 백엔드 환경변수 ────────────────────────────────────────────────
# 3차 라운드(전송 비용 분해)에서 팔이 셋이 됐다 — 같은 webclient 라도 nginx 를 거치느냐
# 워커 직결이냐가 다른 팔이다(docs/decisions/grpc-webclient-transport-cost-breakdown.md §2).
# 4차는 «webclient» 를 webclient-nginx 의 별칭으로 받는다(배포 형상 그대로).
arm_client_type() {
  case "$1" in
    grpc) echo grpc ;;
    webclient|webclient-nginx|webclient-direct) echo webclient ;;
    *) echo "🔴 모르는 팔: $1" >&2; exit 1 ;;
  esac
}

arm_nginx_host() {
  case "$1" in
    grpc) echo "-" ;;                       # gRPC 는 nginx 를 안 거친다(8585 직결)
    webclient|webclient-nginx) echo "ai-nginx" ;;
    webclient-direct) echo "shadowfit-ai" ;; # 홉 없이 워커 0 직결
  esac
}

# 팔 전환 = 백엔드 재기동. 스타트업 프로퍼티(@ConditionalOnProperty)라 달리 방법이 없다.
switch_arm() {
  local arm=$1
  local ct host
  ct=$(arm_client_type "$arm")
  host=$(arm_nginx_host "$arm")
  echo "## 팔 전환 → $arm (client-type=$ct nginx-host=$host) ($(date -u +%T))"
  # 🔴 출력을 버리지 않는다. 예전엔 >/dev/null 2>&1 이라 compose 가 실패해도 조용했고,
  #    그러면 «옛 팔의 컨테이너가 그대로 살아 있는데 새 팔이라고 믿는» 상태가 된다
  #    (2차 로컬 스모크에서 실제로 났다).
  ( cd "$COMPOSE_DIR" && AI_CLIENT_TYPE="$ct" AI_NGINX_HOST="${host}" \
      AI_CHANNEL_POOL_SIZE="$CHANNEL_POOL_SIZE" \
      docker compose up -d --force-recreate shadowfit-backend ) >> "$OUT/compose.log" 2>&1
  local up_rc=$?
  [ "$up_rc" -eq 0 ] || { echo "🔴 compose up 실패(rc=$up_rc) — $OUT/compose.log 를 볼 것"; exit 1; }
  # 헬스가 UP 이 될 때까지. curl 자체 재시도라 sleep 루프를 안 쓴다.
  curl -s -m 300 --retry 100 --retry-delay 3 --retry-all-errors -o /dev/null "$ACTUATOR/actuator/health" || true

  # 🔴 게이트 둘. 프로퍼티만 보면 조립 실패를 못 잡고, 팔 B·C 는 client-type 이 같아서
  #    base-url 까지 봐야 «홉을 거치는 팔» 과 «직결 팔» 이 갈린다.
  local got_ct got_host got_pool
  got_ct=$(backend_env AI_CLIENT_TYPE)
  got_host=$(backend_env AI_NGINX_HOST)
  got_pool=$(backend_env AI_CHANNEL_POOL_SIZE)
  [ "$got_ct" = "$ct" ] || { echo "🔴 client-type 이 안 바뀌었다(got=$got_ct want=$ct) — 중단"; exit 1; }
  if [ "$ct" = "webclient" ] && [ "$got_host" != "$host" ]; then
    echo "🔴 nginx-host 가 안 바뀌었다(got=$got_host want=$host) — B·C 가 구분이 안 된다, 중단"; exit 1
  fi
  [ "$got_pool" = "$CHANNEL_POOL_SIZE" ] || { echo "🔴 채널 풀이 안 바뀌었다(got=$got_pool want=$CHANNEL_POOL_SIZE) — 중단"; exit 1; }
  echo "   AI_CLIENT_TYPE=$got_ct · AI_NGINX_HOST=$got_host · POOL=$got_pool"
}

# 컨테이너 환경변수 하나. Windows 셸에서 돌려도 CR 이 안 섞이게 지운다.
backend_env() { docker exec shadowfit-backend printenv "$1" 2>/dev/null | tr -d '\r'; }
ai_env()      { docker exec shadowfit-ai      printenv "$1" 2>/dev/null | tr -d '\r'; }

# ── 계정 준비 (측정 대상 아님 — 레이트리밋 아래로 페이싱) ────────────────
# 결과: $OUT/tokens.txt · $OUT/emails.txt, 그리고 호출자 셸에 READY(개수)·POOL(토큰 배열).
prepare_accounts() {
  TOKENS="$OUT/tokens.txt"; EMAILS="$OUT/emails.txt"
  if [ ! -s "$TOKENS" ]; then
    : > "$TOKENS"; : > "$EMAILS"
    echo "## 계정 준비 $ACCOUNTS 개 (간격 ${PREP_SLEEP}s)"
    local i stamp email tok code
    for i in $(seq 1 "$ACCOUNTS"); do
      # 🔴 username 도 유니크다("이미 사용 중인 닉네임입니다" 400). 이메일에만 타임스탬프를 넣으면
      #    같은 DB 에서 두 번째 실행부터 전 계정이 조용히 실패한다 — 새 DB 에서는 첫 판만
      #    통과해서 안 보이는 잠복 결함이라 로컬 스모크에서야 걸렸다.
      stamp=$(date +%s%N)
      email="ablat${i}_${stamp}@test.local"
      curl -s -o /dev/null -m 30 -X POST "$BASE/member/signup" -H 'Content-Type: application/json' \
        -d "{\"username\":\"ablat${i}_${stamp}\",\"email\":\"$email\",\"password\":\"$PASSWORD\",\"sex\":\"MALE\",\"role\":\"USER\"}"
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
}

# ── 사이클 하나 = 세션 시작 → 재부착(큰 요청) → 종료(작은 요청) ─────────
# 4차의 k6 스크립트(k6/ai_call_cycle.js)가 같은 세 요청을 보낸다 — 한쪽을 고치면 다른 쪽도.
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

scrape() { curl -s -m 30 "$ACTUATOR/actuator/prometheus" | grep -E '^shadowfit_ai_call_seconds' > "$OUT/scrape/$1.txt"; }

# 🔴 stop 은 **아웃박스 발행기가 비동기로** 보낸다. 사이클이 끝난 «순간» 에는 아직 안 나간
#    stop 이 남아 있고, 그대로 팔을 바꾸면 그것들이 **다음 팔의 백엔드에서** 나가 다른 팔로
#    집계된다(protocol 태그는 «보낸 백엔드» 를 따른다). 고정 sleep 으로는 그걸 보장 못 한다.
#    그래서 «stop 카운트가 더 안 늘 때까지» 기다린다.
stop_count() {
  curl -s -m 30 "$ACTUATOR/actuator/prometheus" \
    | awk '/^shadowfit_ai_call_seconds_count\{/ && /rpc="stop"/ { s += $2 } END { printf "%d", s+0 }'
}

drain() {
  local label=$1 prev=-1 now stable=0 i=0
  while [ "$i" -lt 60 ]; do            # 최대 5분(5초 × 60)
    now=$(stop_count)
    if [ "$now" = "$prev" ]; then
      stable=$((stable+1))
      [ "$stable" -ge 2 ] && { echo "   배수 완료($label) — stop 누적 $now"; return 0; }
    else
      stable=0
    fi
    prev=$now; i=$((i+1)); sleep 5
  done
  echo "   ⚠️ 배수 미완($label) — stop 누적이 5분간 안 멎었다. 이 블록은 팔 귀속이 의심스럽다"
  return 1
}

# 라틴 방격 — 팔이 셋이면 순서 반전만으로는 부족하다. 블록마다 한 칸씩 회전시킨다
# (A B C · B C A · C A B · …). 팔과 «판 순서» 가 같은 축에 겹치면 원리적으로 분리가 안 된다
# ([[feedback_measure_design_needs_repeats]]).
rotate() {  # $1=회전 수, 나머지=목록
  local k=$1; shift
  local -a a=("$@")
  local n=${#a[@]} i out=""
  for ((i=0;i<n;i++)); do out="$out ${a[$(( (i + k) % n ))]}"; done
  echo "$out"
}
