#!/usr/bin/env bash
# MySQL 논리 백업(mysqldump) — 운영 compose 의 `shadowfit-mysql` 컨테이너를 호스트에서 덤프해 압축 보관한다.
#
# 이 스크립트가 주는 것 / 안 주는 것 (먼저 적는다):
#  ✅ 하루 한 번(cron·systemd timer) 돌리면 «최대 하루치» 를 잃는 선에서 되돌릴 수 있다 — RPO = 백업 주기.
#  ✅ --single-transaction 이라 InnoDB 테이블은 쓰기를 막지 않고 일관된 시점으로 뜬다(이 저장소 테이블은 전부 InnoDB).
#  ❌ 사고 직전 시각으로 되돌리는 PITR 은 아니다 — 그건 binlog 가 필요하다(docs/decisions/backup-restore-rto-rpo.md §2).
#  ❌ 호스트를 통째로 잃으면 같이 사라진다. BACKUP_S3_URI 로 밖에 복사하게 해 뒀지만 **이 경로는 실제로 돌려 보지 못했다**
#     (aws CLI·버킷이 없는 환경에서 만들었다). 첫 운영 실행 때 확인할 것.
#  ❌ 큰 DB 에는 맞지 않는다. 논리 덤프는 복구가 느리다(물리 백업의 약 7배 — 같은 문서). 소규모 베타 규모를 가정한다.
#
# 환경변수(모두 선택):
#   MYSQL_CONTAINER   덤프할 컨테이너 이름            (기본 shadowfit-mysql)
#   MYSQL_DATABASE    덤프할 DB                       (기본 shadowfit)
#   BACKUP_DIR        보관 디렉터리                   (기본 /var/backups/shadowfit)
#   BACKUP_KEEP       보관할 개수(최신순)              (기본 14)
#   BACKUP_S3_URI     예: s3://my-bucket/shadowfit    (비우면 밖으로 안 보낸다)
#   BACKUP_MIN_BYTES  이보다 작은 덤프는 실패로 본다   (기본 1024)
#
# 비밀번호는 호스트 명령행에 올리지 않는다 — 컨테이너 안의 MYSQL_ROOT_PASSWORD 를 컨테이너 안에서만 쓴다.
# 종료 코드: 0 성공 · 1 일반 실패 · 2 이미 실행 중 · 3 로컬은 성공했으나 S3 전송 실패
set -euo pipefail

# Windows Git Bash 에서 `/var/...` 같은 인자가 경로로 변환되는 것을 막는다(리눅스에서는 무해).
export MSYS_NO_PATHCONV=1

CONTAINER="${MYSQL_CONTAINER:-shadowfit-mysql}"
DB="${MYSQL_DATABASE:-shadowfit}"
DIR="${BACKUP_DIR:-/var/backups/shadowfit}"
KEEP="${BACKUP_KEEP:-14}"
S3_URI="${BACKUP_S3_URI:-}"
MIN_BYTES="${BACKUP_MIN_BYTES:-1024}"

log() { printf '%s [backup] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "실패: $*" >&2; exit "${2:-1}"; }

case "$KEEP" in ''|*[!0-9]*) die "BACKUP_KEEP 는 양의 정수여야 한다(=$KEEP)";; esac
[ "$KEEP" -ge 1 ] || die "BACKUP_KEEP 는 1 이상이어야 한다(=$KEEP)"
# DB 이름은 셸 인용 없이 쓰이는 자리가 있어 문자를 제한한다.
case "$DB" in *[!A-Za-z0-9_]*|'') die "MYSQL_DATABASE 에는 영문·숫자·_ 만 쓸 수 있다(=$DB)";; esac

command -v docker >/dev/null 2>&1 || die "docker 가 없다"
docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -qx true \
  || die "컨테이너 '$CONTAINER' 가 실행 중이 아니다"

mkdir -p "$DIR"

# 동시 실행 방지 — cron 이 겹쳐 돌면 같은 시각 파일을 두 프로세스가 쓴다. mkdir 은 원자적이다(flock 이 없는 환경도 된다).
LOCK="$DIR/.backup.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  # 프로세스가 죽어 잠금만 남았을 수 있다 — 소유 PID 가 살아 있는지 본다.
  old_pid="$(cat "$LOCK/pid" 2>/dev/null || true)"
  if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
    die "이미 실행 중이다(pid $old_pid)" 2
  fi
  log "남아 있던 잠금을 치운다(pid ${old_pid:-?} 는 없다)"
  rm -rf "$LOCK"
  mkdir "$LOCK" || die "잠금을 못 잡았다" 2
fi
echo $$ > "$LOCK/pid"

STAMP="$(date '+%Y%m%d-%H%M%S')"
FINAL="$DIR/${DB}-${STAMP}.sql.gz"
TMP="$FINAL.partial"
cleanup() { rm -f "$TMP"; rm -rf "$LOCK"; }
trap cleanup EXIT

log "덤프 시작: 컨테이너=$CONTAINER DB=$DB → $FINAL"

# --single-transaction: 잠금 없이 일관된 스냅샷. --routines/--triggers/--events: 코드 밖 객체도 포함.
# --set-gtid-purged=OFF: 복구 대상이 다른 서버여도 GTID 충돌이 없게. --hex-blob: 이진 컬럼 안전.
# --default-character-set=utf8mb4: 한글이 깨지지 않게(서버 기본과 같다).
# pipefail 이라 mysqldump 가 실패하면 gzip 이 성공해도 전체가 실패다.
docker exec "$CONTAINER" sh -c '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot \
    --single-transaction --routines --triggers --events \
    --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 \
    '"$DB"'
' | gzip -c > "$TMP" || die "mysqldump 가 실패했다"

# 검증 — «파일이 생겼다» 와 «쓸 수 있는 백업이다» 는 다르다.
gzip -t "$TMP" 2>/dev/null || die "압축 파일이 손상됐다"
size="$(wc -c < "$TMP" | tr -d ' ')"
[ "$size" -ge "$MIN_BYTES" ] || die "덤프가 너무 작다(${size}B < ${MIN_BYTES}B) — 비어 있는 DB 를 떴을 수 있다"
# mysqldump 는 끝까지 갔을 때만 마지막에 이 줄을 쓴다. 중간에 끊긴 덤프를 잡는다.
gzip -dc "$TMP" | tail -n 3 | grep -q -- '-- Dump completed' || die "덤프가 끝까지 가지 못했다('Dump completed' 줄이 없다)"

mv "$TMP" "$FINAL"
log "덤프 완료: $(wc -c < "$FINAL" | tr -d ' ')B"

# 보관 개수 정리 — 이름이 시각순이라 정렬하면 오래된 것이 앞이다. 방금 만든 것은 항상 남는다.
count=0
# shellcheck disable=SC2012
for f in $(ls -1 "$DIR"/"${DB}"-*.sql.gz 2>/dev/null | sort -r); do
  count=$((count + 1))
  if [ "$count" -gt "$KEEP" ]; then
    rm -f "$f"
    log "오래된 백업 삭제: $(basename "$f")"
  fi
done

# 호스트 밖으로 복사(선택). 실패해도 로컬 백업은 남기고 종료 코드 3 으로 알린다.
if [ -n "$S3_URI" ]; then
  if ! command -v aws >/dev/null 2>&1; then
    log "BACKUP_S3_URI 가 설정됐지만 aws CLI 가 없다 — 밖으로 복사하지 못했다" >&2
    exit 3
  fi
  if aws s3 cp "$FINAL" "${S3_URI%/}/$(basename "$FINAL")" --only-show-errors; then
    log "S3 전송 완료: ${S3_URI%/}/$(basename "$FINAL")"
  else
    log "S3 전송 실패 — 로컬 백업은 남아 있다" >&2
    exit 3
  fi
fi

log "끝"
