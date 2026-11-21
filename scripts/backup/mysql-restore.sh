#!/usr/bin/env bash
# mysql-backup.sh 가 만든 덤프(.sql.gz)를 되살리거나, «되살려진다» 를 확인한다.
#
# 백업은 되살려 보기 전까지 백업이 아니다 — 파일이 있다는 것과 쓸 수 있다는 것은 다르다. 그래서 기본 동작은 **확인 모드**다:
# 운영 DB 를 건드리지 않고 임시 DB 에 덤프를 부어 테이블·행 수를 보여 준 뒤 임시 DB 를 지운다. 주기적으로(예: 월 1회) 돌릴 것.
#
# 사용법:
#   mysql-restore.sh <dump.sql.gz>                     확인 모드(기본) — 임시 DB 에 부어 보고 지운다. 운영 DB 는 안 건드린다
#   mysql-restore.sh <dump.sql.gz> --keep              확인 후 임시 DB 를 지우지 않는다(내용을 직접 볼 때)
#   mysql-restore.sh <dump.sql.gz> --into <db>         실제 복구 — 지정한 DB 에 붓는다. 이미 테이블이 있으면 거절한다
#   mysql-restore.sh <dump.sql.gz> --into <db> --force 이미 있는 DB 의 테이블을 덮어쓴다(덤프에 있는 테이블은 DROP 후 재생성)
#
# 호스트를 잃은 뒤의 실제 복구 순서는 docs/21-backup-and-restore.md 에 있다. 운영 DB(shadowfit)에 --into 로 부을 때는
# **백엔드를 먼저 멈출 것** — 붓는 동안 쓰기가 들어오면 덤프 시점과 섞인다.
#
# 환경변수: MYSQL_CONTAINER (기본 shadowfit-mysql)
# 종료 코드: 0 성공 · 1 실패
set -euo pipefail
export MSYS_NO_PATHCONV=1

CONTAINER="${MYSQL_CONTAINER:-shadowfit-mysql}"

log() { printf '%s [restore] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "실패: $*" >&2; exit 1; }
usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

DUMP=""
INTO=""
FORCE=0
KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --into) [ $# -ge 2 ] || usage; INTO="$2"; shift 2;;
    --force) FORCE=1; shift;;
    --keep) KEEP=1; shift;;
    -h|--help) usage;;
    -*) die "알 수 없는 옵션: $1";;
    *) [ -z "$DUMP" ] || die "덤프 파일은 하나만 받는다"; DUMP="$1"; shift;;
  esac
done
[ -n "$DUMP" ] || usage
[ -f "$DUMP" ] || die "덤프 파일이 없다: $DUMP"
if [ -n "$INTO" ]; then
  case "$INTO" in *[!A-Za-z0-9_]*|'') die "--into 의 DB 이름에는 영문·숫자·_ 만 쓸 수 있다(=$INTO)";; esac
  [ "$KEEP" -eq 0 ] || die "--keep 은 확인 모드에서만 쓴다"
fi

command -v docker >/dev/null 2>&1 || die "docker 가 없다"
docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -qx true \
  || die "컨테이너 '$CONTAINER' 가 실행 중이 아니다"

# 컨테이너 안에서만 비밀번호를 쓴다 — 호스트 명령행·환경에 올리지 않는다.
mysql_exec() { docker exec -i "$CONTAINER" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 "$@"' sh "$@"; }
sql() { mysql_exec -N -B -e "$1"; }

# 부어 보기 전에 파일부터 본다 — 깨진 파일을 DB 에 부으면 반쯤 들어간 채로 멈춘다.
gzip -t "$DUMP" 2>/dev/null || die "압축 파일이 손상됐다: $DUMP"
gzip -dc "$DUMP" | tail -n 3 | grep -q -- '-- Dump completed' \
  || die "덤프가 끝까지 가지 못한 파일이다('Dump completed' 줄이 없다): $DUMP"

if [ -n "$INTO" ]; then
  TARGET="$INTO"
  exists="$(sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$TARGET'")"
  if [ "$exists" = "1" ]; then
    tables="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET'")"
    if [ "$tables" != "0" ] && [ "$FORCE" -ne 1 ]; then
      die "'$TARGET' 에 이미 테이블이 ${tables}개 있다 — 덮어쓰려면 --force (백엔드를 멈췄는지 확인할 것)"
    fi
  else
    sql "CREATE DATABASE \`$TARGET\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
    log "DB 생성: $TARGET"
  fi
  log "실제 복구: $DUMP → $TARGET"
else
  TARGET="restore_verify_$(date '+%Y%m%d%H%M%S')_$$"
  sql "CREATE DATABASE \`$TARGET\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
  log "확인 모드: 임시 DB '$TARGET' 에 부어 본다(운영 DB 는 건드리지 않는다)"
  drop_temp() {
    if [ "$KEEP" -eq 1 ]; then
      log "임시 DB '$TARGET' 를 남겨 둔다(--keep). 지우려면: DROP DATABASE \`$TARGET\`"
    else
      sql "DROP DATABASE IF EXISTS \`$TARGET\`" || log "임시 DB '$TARGET' 를 지우지 못했다 — 직접 지울 것" >&2
    fi
  }
  trap drop_temp EXIT
fi

start="$(date +%s)"
gzip -dc "$DUMP" | mysql_exec "$TARGET" || die "붓는 중 실패했다 — 대상 DB 가 반쯤 채워졌을 수 있다"
elapsed=$(( $(date +%s) - start ))
log "부음 완료: ${elapsed}초"

# 부은 결과를 요약한다 — «에러 없이 끝났다» 만으로는 테이블이 비어 있어도 성공처럼 보인다.
tables="$(sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$TARGET' AND table_type='BASE TABLE' ORDER BY table_name")"
[ -n "$tables" ] || die "부은 뒤 테이블이 하나도 없다 — 빈 덤프이거나 다른 DB 용 덤프다"
n_tables="$(printf '%s\n' "$tables" | wc -l | tr -d ' ')"
log "테이블 ${n_tables}개 — 행 수(정확한 COUNT):"
total=0
for t in $tables; do
  c="$(sql "SELECT COUNT(*) FROM \`$TARGET\`.\`$t\`")"
  total=$((total + c))
  printf '    %-40s %12s\n' "$t" "$c"
done
log "행 합계: $total"

# 마이그레이션 버전이 있으면 함께 보여 준다 — «어느 시점의 스키마인가» 가 복구 직후 가장 먼저 필요한 정보다.
if printf '%s\n' "$tables" | grep -qx 'flyway_schema_history'; then
  ver="$(sql "SELECT CONCAT('V', version, ' (', description, ')') FROM \`$TARGET\`.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1" || true)"
  log "마지막 Flyway 마이그레이션: ${ver:-(없음)}"
fi

if [ -n "$INTO" ]; then
  log "복구 완료 → '$TARGET'. 백엔드를 이 DB 로 붙인 뒤 flyway 가 더 올릴 것이 있는지(기동 로그) 확인할 것"
else
  log "확인 완료 — 이 덤프는 부어진다"
fi
