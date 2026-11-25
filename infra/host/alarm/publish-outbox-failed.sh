#!/usr/bin/env bash
# 백엔드의 shadowfit.outbox.failed 게이지(= FAILED 로 남은 outbox 행 수)를 CloudWatch 로 올린다.
# 1분마다 shadowfit-outbox-metric.timer 가 부른다.
#
# 올리는 지표 둘 (네임스페이스 ShadowFit):
#   OutboxFailed     지금 FAILED 로 남아 있는 행 수. 대시보드·확인용. 알람은 걸지 않는다 —
#                    FAILED 행은 90일 보관(outbox.retention.failed-days)이라 값이 한 번 오르면
#                    오래 0 이 되지 않아서, 이 값에 알람을 걸면 두 번째 실패부터는 알리지 못한다.
#   OutboxFailedNew  직전 실행 이후 «새로» 늘어난 행 수(음수는 0). 알람은 이 값에 건다.
#
# 관리 포트(9090)는 호스트로 매핑하지 않으므로 컨테이너 안에서 읽는다(docker-compose.prod.yml).
# 못 읽으면 지표를 올리지 않고 실패로 끝낸다 — «0 으로 보고»하면 백엔드가 죽어도 평온해 보인다.
# 지표가 안 올라오는 상태는 CloudWatch 의 «데이터 없음» 알람(infra/terraform/alarm.tf)이 잡는다.
#
# 환경: AWS_REGION(host.env). 시험용으로 BACKEND_CONTAINER·STATE_FILE 을 바꿀 수 있다.
set -euo pipefail

CONTAINER="${BACKEND_CONTAINER:-shadowfit-backend}"
STATE_FILE="${STATE_FILE:-/var/lib/shadowfit/outbox-failed.last}"
NAMESPACE="ShadowFit"

log() { echo "[outbox-metric] $*"; }

if ! body="$(docker exec "$CONTAINER" wget -qO- http://localhost:9090/actuator/prometheus)"; then
  log "백엔드 지표를 읽지 못했다 (container=$CONTAINER) — 지표를 올리지 않는다" >&2
  exit 1
fi

# `shadowfit_outbox_failed{application="shadowfit"} 3.0` 또는 태그 없는 `shadowfit_outbox_failed 3.0`
raw="$(printf '%s\n' "$body" | awk '$1 ~ /^shadowfit_outbox_failed(\{|$)/ { print $NF; exit }')"
if [[ -z "$raw" ]]; then
  log "shadowfit_outbox_failed 지표가 응답에 없다 — 지표를 올리지 않는다" >&2
  exit 1
fi
if ! [[ "$raw" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  log "shadowfit_outbox_failed 값을 숫자로 읽지 못했다: '$raw'" >&2
  exit 1
fi
current="${raw%%.*}"

previous=0
if [[ -r "$STATE_FILE" ]]; then
  previous="$(cat "$STATE_FILE")"
  [[ "$previous" =~ ^[0-9]+$ ]] || previous=0
fi
# 정리(retention)가 FAILED 행을 지우면 current 가 previous 보다 작아진다 — 그건 «새 실패»가 아니다.
new=$(( current > previous ? current - previous : 0 ))

region_args=()
[[ -n "${AWS_REGION:-}" ]] && region_args=(--region "$AWS_REGION")

aws cloudwatch put-metric-data "${region_args[@]}" --namespace "$NAMESPACE" --metric-data \
  "MetricName=OutboxFailed,Value=${current},Unit=Count" \
  "MetricName=OutboxFailedNew,Value=${new},Unit=Count"

# 올리는 데 성공한 뒤에만 기준값을 옮긴다 — 실패하면 다음 실행이 같은 증가분을 다시 올린다.
mkdir -p "$(dirname "$STATE_FILE")"
printf '%s' "$current" > "$STATE_FILE"
log "OutboxFailed=${current} OutboxFailedNew=${new}"
