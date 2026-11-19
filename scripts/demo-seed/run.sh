#!/usr/bin/env bash
# 데모 데이터로 로컬 DB 를 갈아엎는다 — 사용자 데이터 전부 삭제 후 재생성. 마스터 데이터(종목·기준 좌표)는 남긴다.
#   bash scripts/demo-seed/run.sh
# 전제: docker compose 스택(mysql·backend)이 떠 있다. Windows 면 Edge 로 사진을 찍는다(없으면 사진 없이 진행).
# 모든 계정 비밀번호: Shadow1234!
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
HERE="$ROOT/scripts/demo-seed"
WORK="${TMPDIR:-/tmp}/shadowfit-demo-seed"
PASSWORD='Shadow1234!'
API="${API:-http://localhost:8080}"
mysql_exec() { docker exec -i shadowfit-mysql mysql --default-character-set=utf8mb4 -u shadowfit -pshadowfit shadowfit "$@" 2> >(grep -Ev "Using a password|World-writable" >&2); }

mkdir -p "$WORK"
echo "1) 백업 → $WORK/backup.sql"
docker exec shadowfit-mysql mysqldump -u shadowfit -pshadowfit --single-transaction shadowfit > "$WORK/backup.sql" 2>/dev/null

echo "2) BCrypt 해시 얻기 (임시 가입 → 해시 읽기)"
TMP_EMAIL="seed-hash-$$@shadowfit.local"
curl -s -o /dev/null -X POST "$API/member/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"seedhash$$\",\"email\":\"$TMP_EMAIL\",\"password\":\"$PASSWORD\",\"sex\":\"NONE\"}"
HASH=$(mysql_exec -N -e "SELECT password FROM users WHERE email='$TMP_EMAIL'")
[ -n "$HASH" ] || { echo "해시를 얻지 못했다 — 백엔드가 떠 있는지 확인"; exit 1; }

echo "3) 피드 사진 그리기"
PHOTO_DIR="$ROOT/backend/data/feed-photos"
mkdir -p "$PHOTO_DIR"
rm -f "$PHOTO_DIR"/*
node "$HERE/photos.mjs" "$WORK/scenes" >/dev/null
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
if [ -x "$EDGE" ]; then
  for f in "$WORK"/scenes/*.html; do
    n=$(basename "$f" .html)
    "$EDGE" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 --window-size=800,600 \
      --screenshot="$(cygpath -w "$PHOTO_DIR/$n.png")" "file:///$(cygpath -m "$f")" >/dev/null 2>&1
  done
  echo "   $(ls "$PHOTO_DIR" | wc -l) 장"
else
  echo "   Edge 를 못 찾아 사진은 건너뜀 (글은 그대로 생기고 사진 칸만 비어 보인다)"
fi

echo "4) SQL 생성 · 적용"
PASSWORD_HASH="$HASH" node "$HERE/seed.mjs" > "$WORK/seed.sql"
mysql_exec < "$WORK/seed.sql"
mysql_exec -e "SELECT (SELECT COUNT(*) FROM users) users, (SELECT COUNT(*) FROM exercise_sessions) sessions, (SELECT COUNT(*) FROM workout_groups) \`groups\`, (SELECT COUNT(*) FROM group_events) events, (SELECT COUNT(*) FROM notifications) notifications"
echo "완료 — 앱·관리자 웹에 다시 로그인하세요 (토큰이 전부 지워졌다). 비밀번호: $PASSWORD"
