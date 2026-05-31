#!/usr/bin/env bash
# ② 백엔드 격리 부하 테스트 (load-test-strategy.md §3.2) — SavePoseDataBatch gRPC.
# bash 변형 (Windows 외 환경 / Git Bash). PowerShell 판은 run-save-pose-batch.ps1.
#
# 사전조건: gRPC :6565 reflection ON, $INTERNAL_API_TOKEN, 세션 row(더미 801), ghz 설치.
# 사용:
#   export INTERNAL_API_TOKEN="<server-token>"
#   ./run-save-pose-batch.sh smoke      # 경로·인증 검증
#   ./run-save-pose-batch.sh baseline   # 단일 세션 순차
#   ./run-save-pose-batch.sh ramp       # 동시성 step ramp — throughput 천장
set -euo pipefail

MODE="${1:-smoke}"
TARGET="${TARGET:-localhost:6565}"
DATA_FILE="${DATA_FILE:-batch.json}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

: "${INTERNAL_API_TOKEN:?INTERNAL_API_TOKEN 미설정 — export 후 재실행}"
command -v ghz >/dev/null || { echo "ghz 미설치 (README §설치)"; exit 1; }
[ -f "$DATA_FILE" ] || { echo "$DATA_FILE 없음 (gen_batch.py 로 생성)"; exit 1; }
mkdir -p results

METADATA="{\"authorization\":\"Bearer ${INTERNAL_API_TOKEN}\"}"
CALL="ExerciseService.SavePoseDataBatch"
COMMON=(--insecure --call "$CALL" --metadata "$METADATA" --data-file "$DATA_FILE")

case "$MODE" in
  smoke)
    echo "[smoke] 경로·인증 검증 — 5 call, c=1"
    ghz "${COMMON[@]}" -n 5 -c 1 "$TARGET"
    ;;
  baseline)
    echo "[baseline] 단일 세션 순차 — 200 call, c=1"
    ghz "${COMMON[@]}" -n 200 -c 1 -O html -o results/baseline.html "$TARGET"
    echo "리포트: results/baseline.html"
    ;;
  ramp)
    echo "[ramp] 동시성 step 5->100 (10s/step) — throughput 천장 + p99"
    ghz "${COMMON[@]}" \
      --concurrency-schedule=step \
      --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 \
      --concurrency-step-duration=10s \
      -z 210s \
      -O html -o results/ramp.html "$TARGET"
    echo "리포트: results/ramp.html — throughput 평탄 지점 = 천장, 그 p99 를 SLO 와 비교 (doc §11)"
    ;;
  *) echo "알 수 없는 mode: $MODE (smoke|baseline|ramp)"; exit 1 ;;
esac
