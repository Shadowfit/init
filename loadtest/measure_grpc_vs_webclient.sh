#!/usr/bin/env bash
# gRPC vs WebClient — Spring→AI 요청 경로의 전송 계층 A/B 실측
# 설계·배경: docs/decisions/grpc-webclient-empirical-comparison.md (§2 라우팅 중복, §8 스펙, §9 착수 현황)
#
# ## 이 rig 가 답하려는 질문
#
# §2 가 발견한 것은 «어느 AI 프로세스로 보낼까» 를 푸는 메커니즘이 이미 두 개라는 것이다 —
# REST 는 검증된 nginx map(X-AI-Worker), gRPC 는 Spring 이 손으로 짠 ManagedChannel 풀
# (그리고 **손으로 짠 쪽에서만 실제 버그가 났다**). 그래서 묻는다:
#
#     "이미 있는 nginx 라우팅을 그대로 재사용하면, 전송 비용을 얼마나 더 내는가?"
#
# ## 팔 3개 — 왜 2개가 아니라 3개인가
#
#   grpc         ghz  → AI gRPC 직결        (프로덕션 gRPC 모양: nginx 를 건너뛴다)
#   rest-nginx   k6   → ai-nginx → 워커     (프로덕션 REST 모양: 검증된 라우팅 재사용)
#   rest-direct  k6   → 워커 직결           (nginx 홉을 뺀 것)
#
# 🔴 3번째 팔이 없으면 «REST 가 느리다» 가 나와도 그게 **JSON 때문인지 nginx 홉 때문인지**
#    못 가른다. rest-direct 가 그 둘을 분리한다. 두 팔짜리 A/B 는 원인 규명이 아니라
#    승패 판정만 되고, 이 저장소는 그런 결론을 안 쓴다.
#
# ## 공정성 장치 (이게 없으면 다른 걸 재게 된다)
#
#   1. **전 팔 워커 0 고정.** gRPC 팔은 포트 하나(8585)를 치므로 워커 1개에 몰린다.
#      REST 팔을 라우팅 그대로 두면 3개로 흩어져서, 프로토콜이 아니라 **병렬도**를 재게 된다.
#      그래서 REST 팔도 X-AI-Worker: 0 으로 고정한다. 흩어짐 자체는 아래 라우팅 probe 로 따로 본다.
#   2. **같은 논리 요청.** 두 팔이 loadtest/ghz/gen_ab_payloads.py 가 만든 **같은 JSON 파일**을
#      쓴다. ghz 는 그걸 protobuf 로 바꿔 보내고 k6 는 그대로 보낸다 — 달라지는 건 직렬화뿐이다.
#   3. **분석을 안 태운다.** 두 페이로드 모두 핸들러가 일찍 반환하는 입력이다(생성기 docstring).
#      검출기(98.7MB)도 MediaPipe 도 안 낀다. 안 그러면 팔 사이 차이가 분석 시간에 묻힌다.
#   4. **닫힌 루프.** ghz -c/-n 과 k6 shared-iterations 로 «같은 일 N건» 을 시킨다.
#      열린 루프(arrival rate)는 대상이 느려지면 큐를 재게 된다.
#   5. **버림 블록 + 라틴 방격 회전.** 팔 순서를 블록마다 돌린다 — 안 그러면 «팔» 과
#      «판 순서»(JIT 예열·페이지 캐시·이웃 소음)가 분리되지 않는다.
#      ([[feedback_measure_design_needs_repeats]])
#
# ## 안 재는 것
#
#   - 절대 처리량 천장. 이건 닫힌 루프 지연 rig 다.
#   - 콜백 3개(AI→Spring). 그 방향은 Spring 이 단일 인스턴스라 라우팅 문제가 없어서
#     REST 로 바꿔도 얻을 게 없다(§2 마지막 단락) — 애초에 미러가 없다.
#   - Spring 을 통과하는 종단 지연. 이 라운드는 «AI 직접» 층으로 좁혔다(2026-09-10 결정).
#
# ## 필요한 환경변수
#   AI_HOST        대상 AI 박스 주소
#   TOKEN          대상의 INTERNAL_API_TOKEN (AI_PUBLIC_TOKEN 아님 — 다르면 401)
#   OUT            결과 디렉터리
# 선택
#   GRPC_PORT      기본 8585 (워커 0 의 gRPC)
#   NGINX_PORT     기본 8000 (ai-nginx)
#   DIRECT_PORT    워커 0 의 HTTP 를 호스트로 직접 편 포트. 안 주면 rest-direct 팔을
#                  **건너뛰고 그 사실을 표에 남긴다**(조용히 빼지 않는다)
#   CONCS          동시성 목록. 기본 "1 3" — 1 은 무부하 단일요청(기존 gRPC rig 관례),
#                  3 은 AI_WORKER_COUNT 기본값. 근거 없는 숫자를 안 쓴다
#   N              판당 요청 수 (기본 200)
#   BLOCKS         버림 포함 블록 수 (기본 5 = 버림 1 + 유효 4)
#   FRAMES         L 페이로드의 기준 프레임 수 (기본 30 = reference_builder target_length)
#   SESSION_ID     레지스트리에 없어야 하는 세션 id (기본 999000001)
#   UNSUPPORTED_EXERCISE_ID  분석기가 없는 종목 id (기본 99999)

set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${AI_HOST:?AI_HOST 미설정}" "${TOKEN:?TOKEN 미설정 — 대상의 INTERNAL_API_TOKEN}"
: "${OUT:?OUT 미설정}"
GRPC_PORT=${GRPC_PORT:-8585}
NGINX_PORT=${NGINX_PORT:-8000}
DIRECT_PORT=${DIRECT_PORT:-}
CONCS=${CONCS:-"1 3"}
N=${N:-200}
BLOCKS=${BLOCKS:-5}
FRAMES=${FRAMES:-30}
SESSION_ID=${SESSION_ID:-999000001}
UNSUPPORTED_EXERCISE_ID=${UNSUPPORTED_EXERCISE_ID:-99999}
GHZ_BIN=${GHZ_BIN:-/usr/local/bin/ghz}
K6_BIN=${K6_BIN:-/usr/local/bin/k6}
PY=${PY:-python3}
PROTO=${PROTO:-$HERE/../ai-server/app/proto/exercise.proto}
K6_SCRIPT=${K6_SCRIPT:-$HERE/k6/ab_internal_analysis.js}
REST_PREFIX=/api/v1/internal/analysis

die() { echo "🔴 중단 — $*" >&2; exit 1; }

mkdir -p "$OUT/logs" "$OUT/payload" || die "OUT 을 못 만든다: $OUT"
RAW="$OUT/raw.tsv"

command -v "$GHZ_BIN" >/dev/null 2>&1 || [ -x "$GHZ_BIN" ] || die "ghz 를 못 찾았다: $GHZ_BIN"
command -v "$K6_BIN"  >/dev/null 2>&1 || [ -x "$K6_BIN"  ] || die "k6 를 못 찾았다: $K6_BIN"
command -v "$PY"      >/dev/null 2>&1 || die "$PY 가 없다 — 결과 JSON 을 못 읽는다"
[ -f "$PROTO" ] || die "proto 가 없다: $PROTO"
[ -f "$K6_SCRIPT" ] || die "k6 스크립트가 없다: $K6_SCRIPT"

echo "# gRPC vs WebClient — 전송 계층 A/B"
echo "  대상    : $AI_HOST (gRPC $GRPC_PORT · nginx $NGINX_PORT · direct ${DIRECT_PORT:-없음})"
echo "  설계    : ${BLOCKS}블록(0 버림) x 동시성[$CONCS] x 크기[S L] x 팔[grpc rest-nginx${DIRECT_PORT:+ rest-direct}]"
echo "  판당    : n=$N (닫힌 루프)"
echo "  ghz     : $("$GHZ_BIN" --version 2>&1 | head -1)"
echo "  k6      : $("$K6_BIN" version 2>&1 | head -1)"
echo

# ── [1] 페이로드 ────────────────────────────────────────────────────────────
echo "## [1] 페이로드 생성"
"$PY" "$HERE/ghz/gen_ab_payloads.py" --out-dir "$OUT/payload" --frames "$FRAMES" \
  --session-id "$SESSION_ID" --unsupported-exercise-id "$UNSUPPORTED_EXERCISE_ID" \
  || die "페이로드 생성 실패"
echo

ARMS=(grpc rest-nginx)
[ -n "$DIRECT_PORT" ] && ARMS+=(rest-direct)

call_of()    { if [ "$1" = S ]; then echo "ExerciseService.StopAnalysis"; else echo "ExerciseService.ReattachAnalysis"; fi; }
path_of()    { if [ "$1" = S ]; then echo "$REST_PREFIX/stop";            else echo "$REST_PREFIX/reattach"; fi; }
payload_of() { if [ "$1" = S ]; then echo "$OUT/payload/stop.json";       else echo "$OUT/payload/reattach.json"; fi; }
rest_base()  { if [ "$1" = rest-nginx ]; then echo "http://$AI_HOST:$NGINX_PORT"; else echo "http://$AI_HOST:$DIRECT_PORT"; fi; }

printf '{"authorization":"Bearer %s"}' "$TOKEN" > "$OUT/_meta.json"

# ── [2] 프리플라이트 — 못 닿는 팔이 있으면 여기서 멈춘다 ────────────────────
# 이 저장소가 겪은 실패 모양: 한 팔이 조용히 0건으로 비어 있는데 표는 정상으로 보이는 것.
# 그래서 각 팔을 1건씩 먼저 쳐 보고, 하나라도 못 닿으면 시작을 안 한다.
echo "## [2] 프리플라이트 (팔당 1건)"
preflight_fail=0
for arm in "${ARMS[@]}"; do
  if [ "$arm" = grpc ]; then
    "$GHZ_BIN" --insecure --proto "$PROTO" -i "$(dirname "$PROTO")" \
      --call "$(call_of S)" --metadata-file "$OUT/_meta.json" \
      --data-file "$(payload_of S)" -c 1 -n 1 -O json -o "$OUT/logs/_pre-$arm.json" \
      "$AI_HOST:$GRPC_PORT" > "$OUT/logs/_pre-$arm.log" 2>&1
    rc=$?
    if [ $rc -ne 0 ] || ! grep -q '"OK"' "$OUT/logs/_pre-$arm.json" 2>/dev/null; then
      echo "  🔴 $arm — 실패(rc=$rc). $OUT/logs/_pre-$arm.log 를 볼 것"
      preflight_fail=1
    else
      echo "  ✅ $arm"
    fi
  else
    code=$(curl -s -o "$OUT/logs/_pre-$arm.body" -w '%{http_code}' -m 15 \
      -X POST "$(rest_base "$arm")$(path_of S)" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
      -H "X-AI-Worker: 0" --data-binary "@$(payload_of S)" 2>"$OUT/logs/_pre-$arm.log")
    case "$code" in
      200) echo "  ✅ $arm (200)";;
      401) echo "  🔴 $arm — 401. TOKEN 이 INTERNAL_API_TOKEN 이 맞는지 볼 것(AI_PUBLIC_TOKEN 이면 여기서 막힌다)"; preflight_fail=1;;
      404) echo "  🔴 $arm — 404. REST 미러가 없는 이미지다(explore/grpc-webclient-ab 커밋인지 확인)"; preflight_fail=1;;
      000) echo "  🔴 $arm — 못 닿음. 포트 개방·보안그룹을 볼 것"; preflight_fail=1;;
      *)   echo "  🔴 $arm — HTTP $code. $OUT/logs/_pre-$arm.body 를 볼 것"; preflight_fail=1;;
    esac
  fi
done
[ "$preflight_fail" = 0 ] || die "프리플라이트에서 팔이 빠졌다 — 이대로 돌리면 빈 표가 나온다"
echo

# ── [3] 본 측정 ─────────────────────────────────────────────────────────────
printf "block\tconc\tsize\tarm\tcount\tok\tfail\tp50_ms\tp95_ms\tp99_ms\tmax_ms\n" > "$RAW"

emit_ghz() { # $1=json $2=block $3=conc $4=size $5=arm
  "$PY" "$HERE/ab_extract.py" ghz "$1" "$2" "$3" "$4" "$5" "$RAW"
}
emit_k6() {  # $1=json $2=block $3=conc $4=size $5=arm
  "$PY" "$HERE/ab_extract.py" k6 "$1" "$2" "$3" "$4" "$5" "$RAW"
}

run_cell() { # $1=block $2=conc $3=size $4=arm
  local b=$1 c=$2 sz=$3 arm=$4 tag="b${1}-c${2}-${3}-${4}"
  if [ "$arm" = grpc ]; then
    "$GHZ_BIN" --insecure --proto "$PROTO" -i "$(dirname "$PROTO")" \
      --call "$(call_of "$sz")" --metadata-file "$OUT/_meta.json" \
      --data-file "$(payload_of "$sz")" -c "$c" -n "$N" \
      -O json -o "$OUT/logs/$tag.json" "$AI_HOST:$GRPC_PORT" > "$OUT/logs/$tag.log" 2>&1
    emit_ghz "$OUT/logs/$tag.json" "$b" "$c" "$sz" "$arm"
  else
    URL="$(rest_base "$arm")$(path_of "$sz")" TOKEN="$TOKEN" WORKER=0 \
    BODY_FILE="$(payload_of "$sz")" VUS="$c" ITERS="$N" \
      "$K6_BIN" run --quiet --summary-trend-stats "avg,med,p(95),p(99),max" \
      --summary-export="$OUT/logs/$tag.json" "$K6_SCRIPT" > "$OUT/logs/$tag.log" 2>&1
    emit_k6 "$OUT/logs/$tag.json" "$b" "$c" "$sz" "$arm"
  fi
  tail -1 "$RAW" | awk -F'\t' '{printf "      %-11s %-1s c=%-2s  ok=%-4s fail=%-3s p50=%-9s p95=%-9s p99=%s\n",$4,$3,$2,$6,$7,$8,$9,$10}'
}

echo "## [3] 측정"
na=${#ARMS[@]}
for ((b=0; b<BLOCKS; b++)); do
  if [ "$b" = 0 ]; then echo "  ── 블록 $b (버림)"; else echo "  ── 블록 $b"; fi
  for c in $CONCS; do
    for sz in S L; do
      for ((k=0; k<na; k++)); do
        # 라틴 방격 회전 — 블록마다 팔 순서를 한 칸씩 민다
        run_cell "$b" "$c" "$sz" "${ARMS[$(((k+b)%na))]}"
      done
    done
  done
  if [ "$b" = 0 ]; then
    empty=$(awk -F'\t' 'NR>1 && $1==0 && ($5=="FAIL" || $6+0==0) {n++} END{print n+0}' "$RAW")
    [ "$empty" -eq 0 ] || die "버림 블록에서 $empty 칸이 표본을 못 만들었다 — 계속해도 빈 표가 나온다. $OUT/logs/ 를 볼 것"
  fi
done
echo

# ── [4] 라우팅 probe — nginx 가 정말 헤더대로 보내는가 ──────────────────────
# 본 측정은 전 팔을 워커 0 에 고정한다(공정성 장치 1). 그래서 «흩어짐» 은 여기서 따로 본다.
# 자동 판정은 안 한다 — 어느 워커가 받았는지는 대상 박스의 AI 로그에만 있어서,
# 여기서는 대조에 쓸 session_id 만 남긴다(원격 박스 로그를 이 스크립트가 못 읽는다).
echo "## [4] 라우팅 probe (워커 0·1·2 로 1건씩)"
{
  echo "# X-AI-Worker 헤더별 StopAnalysis 1건 — 대상 박스에서 아래 session_id 로 로그를 대조할 것"
  echo "#   docker compose logs shadowfit-ai | grep 'StopAnalysis 수신'"
  echo "# 워커 인덱스별로 다른 pid 가 찍혀야 nginx map 이 실제로 갈라 보낸 것이다."
  for w in 0 1 2; do
    sid=$((999000010 + w))
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 15 \
      -X POST "http://$AI_HOST:$NGINX_PORT$REST_PREFIX/stop" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
      -H "X-AI-Worker: $w" -d "{\"session_id\": $sid}")
    echo "worker=$w session_id=$sid http=$code"
  done
} | tee "$OUT/routing_probe.txt"
echo

# ── [5] 집계 ────────────────────────────────────────────────────────────────
echo "## [5] 집계 — 유효 블록(0 제외)의 중앙값"
{
echo "# gRPC vs WebClient — 전송 계층 A/B"
echo
echo "대상 \`$AI_HOST\` · ${BLOCKS}블록(0 버림) · n=$N/판 · 전 팔 워커 0 고정"
echo
echo "| 동시성 | 크기 | 팔 | p50(ms) | p95(ms) | p99(ms) | fail |"
echo "|--:|:-:|---|--:|--:|--:|--:|"
awk -F'\t' '
  function med(arr, cnt,   i,j,t) {
    for(i=1;i<=cnt;i++) for(j=i+1;j<=cnt;j++) if(arr[j]<arr[i]) {t=arr[i];arr[i]=arr[j];arr[j]=t}
    return (cnt%2) ? arr[int((cnt+1)/2)] : (arr[cnt/2]+arr[cnt/2+1])/2
  }
  NR>1 && $1+0>0 && $5!="FAIL" {
    key=$2"\t"$3"\t"$4; n[key]++
    a50[key,n[key]]=$8+0; a95[key,n[key]]=$9+0; a99[key,n[key]]=$10+0; f[key]+=$7+0
    if(!(key in seen)){seen[key]=1; order[++o]=key}
  }
  END{
    for(i=1;i<=o;i++){
      key=order[i]; c=n[key]
      for(j=1;j<=c;j++){x50[j]=a50[key,j]; x95[j]=a95[key,j]; x99[j]=a99[key,j]}
      split(key,p,"\t")
      printf "| %s | %s | %s | %.3f | %.3f | %.3f | %d |\n", p[1],p[2],p[3], med(x50,c), med(x95,c), med(x99,c), f[key]
    }
  }' "$RAW"
echo
echo "> 🔴 절대값을 인용하지 말 것 — 이 rig 는 닫힌 루프 지연 비교다. 읽는 법은 **팔 사이 델타**다."
echo ">"
echo "> - \`rest-nginx\` − \`rest-direct\` = **nginx 홉 비용** (같은 프로토콜, 홉만 다름)"
echo "> - \`rest-direct\` − \`grpc\` = **프로토콜·직렬화 비용** (같은 홉 수, 형식만 다름)"
echo "> - S 와 L 의 차이가 이 둘을 크기 축으로 다시 가른다 — S 는 고정비, L 은 디코드 비용"
if [ -z "$DIRECT_PORT" ]; then
echo ">"
echo "> ⚠️ **rest-direct 팔이 없다**(DIRECT_PORT 미설정). 그래서 이 판으로는 «REST 가 느리다» 가"
echo "> 나와도 JSON 때문인지 nginx 홉 때문인지 **가를 수 없다.** 원인 규명이 필요하면 워커 0 의"
echo "> HTTP 포트를 호스트로 열고 다시 돌릴 것."
fi
echo
echo "> 원자료: \`raw.tsv\` · 로그: \`logs/\` · 라우팅: \`routing_probe.txt\`"
} | tee "$OUT/table.md"

echo
echo "결과 → $OUT"
