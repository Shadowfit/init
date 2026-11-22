#!/bin/bash
# 호스트에서 도는 배포 스크립트. GitHub Actions 가 SSM Run Command 로 부른다.
#
#   deploy.sh <번들 S3 키> [backend 태그] [ai 태그]
#
# 태그를 생략한 쪽은 지금 떠 있는 태그를 그대로 둔다. backend·ai 이미지는 각자 경로 필터로
# 빌드되므로(cd-backend.yml, cd-ai-server.yml) 같은 커밋에 두 이미지가 다 있다는 보장이 없다 —
# 그래서 compose 의 단일 IMAGE_TAG 를 쓰지 않고 서비스별로 태그를 덮어쓴다.
#
# 실패하면 직전 태그로 되돌린다. ⚠️ 되돌리는 것은 이미지뿐이다. Flyway 가 이미 적용한
# 마이그레이션은 되돌지 않는다 — 스키마 변경은 "확장 → 이전 → 수축" 순으로 나눠 낸다
# (infra/README.md "롤백이 못 하는 것").
set -euo pipefail

BUNDLE_KEY="${1:?번들 S3 키가 필요하다}"
NEW_BACKEND_TAG="${2:-}"
NEW_AI_TAG="${3:-}"

ROOT=/opt/shadowfit
PROJECT=shadowfit
IMAGE_REPO="${IMAGE_REPO:-ghcr.io/shadowfit/init}"
STATE="$ROOT/state.env"

# 동시에 두 배포가 돌면 한쪽이 다른 쪽의 compose 를 덮는다.
exec 9>"$ROOT/deploy.lock"
flock -n 9 || { echo "다른 배포가 진행 중이다"; exit 1; }

# shellcheck disable=SC1091
source "$ROOT/host.env"
export AWS_DEFAULT_REGION="$AWS_REGION"

log() { echo "[deploy $(date +%H:%M:%S)] $*"; }

# ── 상태 ────────────────────────────────────────────────────────────────────
CUR_BACKEND_TAG=""; CUR_AI_TAG=""
[ -f "$STATE" ] && source "$STATE"   # CUR_BACKEND_TAG, CUR_AI_TAG
BACKEND_TAG="${NEW_BACKEND_TAG:-$CUR_BACKEND_TAG}"
AI_TAG="${NEW_AI_TAG:-$CUR_AI_TAG}"
if [ -z "$BACKEND_TAG" ] || [ -z "$AI_TAG" ]; then
  echo "첫 배포에는 backend·ai 태그를 둘 다 줘야 한다 (backend='$BACKEND_TAG' ai='$AI_TAG')"
  exit 1
fi

# ── 번들 ────────────────────────────────────────────────────────────────────
REL="$ROOT/releases/$(basename "$BUNDLE_KEY" .tgz)"
mkdir -p "$REL"
log "번들 내려받기: s3://$DEPLOY_BUCKET/$BUNDLE_KEY"
aws s3 cp "s3://$DEPLOY_BUCKET/$BUNDLE_KEY" /tmp/bundle.tgz --only-show-errors
tar -xzf /tmp/bundle.tgz -C "$REL"
rm -f /tmp/bundle.tgz

# ── 시크릿 → .env ───────────────────────────────────────────────────────────
# compose 는 .env 값의 `$` 를 치환하므로 `$$` 로 이스케이프한다.
# GHCR_* 는 앱 설정이 아니라 레지스트리 로그인용이라 .env 에 넣지 않는다.
log "SSM 에서 설정 읽기: $SSM_PREFIX"
PARAMS="$(aws ssm get-parameters-by-path --path "$SSM_PREFIX" --recursive --with-decryption \
  --query 'Parameters[].[Name,Value]' --output json)"

umask 077
ENV_FILE="$ROOT/.env"
{
  cat "$ROOT/host.env"
  echo "$PARAMS" | jq -r '.[] | select((.[0]|split("/")|last) | startswith("GHCR_") | not)
                            | "\((.[0]|split("/")|last))=\(.[1])"' | sed 's/\$/$$/g'
} > "$ENV_FILE.new"
mv "$ENV_FILE.new" "$ENV_FILE"

GHCR_USER="$(echo "$PARAMS" | jq -r '.[] | select(.[0]|endswith("/GHCR_USER")) | .[1]')"
GHCR_TOKEN="$(echo "$PARAMS" | jq -r '.[] | select(.[0]|endswith("/GHCR_TOKEN")) | .[1]')"
if [ -n "$GHCR_USER" ] && [ -n "$GHCR_TOKEN" ]; then
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin >/dev/null
  log "GHCR 로그인 완료"
else
  log "GHCR 자격증명이 없다 — 패키지가 공개라고 가정하고 진행"
fi

# ── compose 덮어쓰기 파일 ───────────────────────────────────────────────────
# 1) 서비스별 이미지 태그  2) MySQL 데이터를 별도 EBS 에 두기 (named volume → bind mount)
write_override() {
  cat > "$ROOT/override.yml" <<EOF
services:
  shadowfit-backend:
    image: $IMAGE_REPO/backend:$1
  shadowfit-ai:
    image: $IMAGE_REPO/ai-server:$2
  mysql:
    volumes:
      - /mnt/shadowfit/mysql:/var/lib/mysql
      - $REL/mysql/my.cnf:/etc/mysql/conf.d/charset.cnf:ro
EOF
}

dc() {
  docker compose -p "$PROJECT" --project-directory "$REL" \
    --env-file "$ENV_FILE" \
    -f "$REL/docker-compose.prod.yml" -f "$ROOT/override.yml" "$@"
}

wait_healthy() {
  local deadline=$((SECONDS + 300))
  while [ $SECONDS -lt $deadline ]; do
    local ok=1
    for c in shadowfit-mysql shadowfit-backend shadowfit-ai; do
      [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo none)" = "healthy" ] || ok=0
    done
    if [ $ok = 1 ]; then return 0; fi
    sleep 5
  done
  return 1
}

smoke() {
  # 컨테이너 healthy 만으로는 "AI 프록시 경로가 산다"를 보장하지 못한다 — 호스트 포트로 직접 친다.
  curl -fsS --max-time 5 http://localhost:8000/health >/dev/null
}

# 🔴 이 함수는 `if rollout ...` 안에서 불리므로 set -e 가 꺼진다. 각 단계를 && 로 이어야
#    `pull` 이 실패했을 때 "옛 컨테이너가 아직 healthy" 라는 이유로 성공으로 판정되지 않는다.
rollout() {
  write_override "$1" "$2" \
    && dc pull \
    && dc up -d --remove-orphans \
    && wait_healthy \
    && smoke
}

# ── 백업 스크립트·타이머 설치 ──────────────────────────────────────────────
# 앱 배포의 성패와 무관하게 멱등으로 갱신한다. 실패해도 배포를 막지 않는다 — 대신 크게 알린다.
install_backup() {
  mkdir -p "$ROOT/backup" /var/backups/shadowfit
  install -m 0755 "$REL/scripts/backup/mysql-backup.sh"  "$ROOT/backup/mysql-backup.sh"
  install -m 0755 "$REL/scripts/backup/mysql-restore.sh" "$ROOT/backup/mysql-restore.sh"
  install -m 0644 "$REL/infra/host/systemd/shadowfit-backup.service" /etc/systemd/system/
  install -m 0644 "$REL/infra/host/systemd/shadowfit-backup.timer"   /etc/systemd/system/
  systemctl daemon-reload
  systemctl enable --now shadowfit-backup.timer
}
if install_backup; then
  log "백업 타이머 설치 완료: $(systemctl list-timers shadowfit-backup.timer --no-legend | head -1)"
else
  log "⚠️ 백업 타이머 설치 실패 — 앱 배포는 계속하지만 백업이 안 돌고 있을 수 있다"
fi

log "배포: backend=$BACKEND_TAG ai=$AI_TAG (이전: backend=${CUR_BACKEND_TAG:-없음} ai=${CUR_AI_TAG:-없음})"
if rollout "$BACKEND_TAG" "$AI_TAG"; then
  cat > "$STATE" <<EOF
CUR_BACKEND_TAG=$BACKEND_TAG
CUR_AI_TAG=$AI_TAG
PREV_BACKEND_TAG=${CUR_BACKEND_TAG}
PREV_AI_TAG=${CUR_AI_TAG}
EOF
  log "성공"
else
  log "실패 — 로그 마지막 줄:"
  docker logs --tail 40 shadowfit-backend 2>&1 || true
  if [ -n "$CUR_BACKEND_TAG" ] && [ -n "$CUR_AI_TAG" ]; then
    log "직전 태그로 롤백: backend=$CUR_BACKEND_TAG ai=$CUR_AI_TAG"
    if rollout "$CUR_BACKEND_TAG" "$CUR_AI_TAG"; then
      log "롤백 완료 (이미지만 — DB 스키마는 그대로)"
    else
      log "롤백도 실패 — 수동 확인 필요"
    fi
  fi
  exit 1
fi

# 오래된 릴리스·이미지 정리 (최근 5개 유지)
ls -1dt "$ROOT"/releases/*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf
docker image prune -f >/dev/null
