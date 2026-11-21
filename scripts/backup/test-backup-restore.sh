#!/usr/bin/env bash
# mysql-backup.sh · mysql-restore.sh 왕복 시험 — 일회용 MySQL 컨테이너를 띄워 실제로 덤프하고 되살려 본다.
#   bash scripts/backup/test-backup-restore.sh
# 필요한 것: docker (mysql:8.0 이미지를 내려받을 수 있어야 한다). 끝나면 컨테이너와 임시 디렉터리를 지운다.
#
# 스크립트를 «읽어서» 맞아 보이는 것과 «돌려서» 맞는 것은 다르다 — 이 시험이 보는 것:
#  한글·BLOB·파티션 테이블·트리거·루틴이 왕복 후 그대로인가 / 보관 개수 정리 / 잘못된 입력에서 실패하는가 / 덮어쓰기 보호 / 잠금.
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP="$HERE/mysql-backup.sh"
RESTORE="$HERE/mysql-restore.sh"
C="bk-test-$$"
WORK="$(mktemp -d)"
PASS=0
FAIL=0

cleanup() { docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

ok()   { PASS=$((PASS + 1)); printf '  ✓ %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  ✗ %s\n' "$1"; }
check() { # check "설명" 명령...  — 명령이 성공하면 통과
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi
}
expect_fail() { # expect_fail "설명" 기대코드 명령...
  local name="$1" want="$2"; shift 2
  local got=0
  "$@" >"$WORK/out.txt" 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then ok "$name (종료 코드 $got)"; else bad "$name — 종료 코드 $got, 기대 $want"; sed 's/^/      /' "$WORK/out.txt" | tail -5; fi
}
q() { docker exec -i "$C" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 "$@"' sh -N -B "$@"; }

echo "일회용 MySQL 기동..."
docker run -d --name "$C" -e MYSQL_ROOT_PASSWORD=testpw -e MYSQL_DATABASE=shadowfit mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null
# 공식 이미지는 초기화용 임시 서버를 띄웠다 내린 뒤 본 서버를 올린다 — 본 서버(port 3306)가 준비됐다는 줄을 기다린다.
for _ in $(seq 1 90); do
  if docker logs "$C" 2>&1 | grep -qE "ready for connections.*port: 3306"; then break; fi
  sleep 2
done
docker logs "$C" 2>&1 | grep -qE "ready for connections.*port: 3306" || { echo "MySQL 이 뜨지 않았다"; docker logs "$C" | tail -20; exit 1; }

echo "시험 데이터 준비..."
q shadowfit -e "
CREATE TABLE members (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL, bio TEXT, avatar BLOB) ENGINE=InnoDB;
INSERT INTO members (name, bio, avatar) VALUES ('홍길동', '스쿼트를 좋아함 💪', UNHEX('00FF10AB')), ('Kim', 'it''s a \"quote\"', NULL);
CREATE TABLE pose_data (id BIGINT NOT NULL, created_at DATETIME NOT NULL, note VARCHAR(20), PRIMARY KEY (id, created_at))
  ENGINE=InnoDB PARTITION BY RANGE COLUMNS(created_at) (
    PARTITION p202611 VALUES LESS THAN ('2026-12-01'), PARTITION p202612 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE));
INSERT INTO pose_data VALUES (1,'2026-11-05 10:00:00','a'),(2,'2026-12-05 10:00:00','b'),(3,'2027-03-01 00:00:00','c');
CREATE TABLE audit (id INT AUTO_INCREMENT PRIMARY KEY, msg VARCHAR(100)) ENGINE=InnoDB;
CREATE TRIGGER trg_members_ai AFTER INSERT ON members FOR EACH ROW INSERT INTO audit(msg) VALUES (CONCAT('added ', NEW.name));
CREATE FUNCTION fn_double(x INT) RETURNS INT DETERMINISTIC RETURN x * 2;
CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200), success TINYINT(1));
INSERT INTO flyway_schema_history VALUES (1,'1','baseline',1),(2,'36','add member consents',1);
INSERT INTO members (name, bio) VALUES ('트리거확인', 'x');
"
src_members="$(q shadowfit -e 'SELECT COUNT(*) FROM members')"
src_pose="$(q shadowfit -e 'SELECT COUNT(*) FROM pose_data')"
src_audit="$(q shadowfit -e 'SELECT COUNT(*) FROM audit')"

export MYSQL_CONTAINER="$C" MYSQL_DATABASE=shadowfit
B="$WORK/backups"

echo
echo "백업"
BACKUP_DIR="$B" bash "$BACKUP" >"$WORK/backup.log" 2>&1 && ok "백업 성공(종료 코드 0)" || { bad "백업 실패"; cat "$WORK/backup.log"; }
f="$(ls -1 "$B"/shadowfit-*.sql.gz 2>/dev/null | head -1 || true)"
[ -n "$f" ] && ok "덤프 파일이 생겼다: $(basename "$f")" || bad "덤프 파일이 없다"
check "압축이 정상이다" gzip -t "$f"
check "partial 파일이 남지 않았다" test -z "$(ls "$B" | grep '\.partial$' || true)"
check "잠금 디렉터리가 치워졌다" test ! -e "$B/.backup.lock"
check "트리거가 덤프에 있다" sh -c "gzip -dc '$f' | grep -q 'CREATE.*TRIGGER.*trg_members_ai'"
check "함수(routine)가 덤프에 있다" sh -c "gzip -dc '$f' | grep -q 'fn_double'"
check "비밀번호가 덤프·로그에 안 나온다" sh -c "! gzip -dc '$f' | grep -q testpw && ! grep -q testpw '$WORK/backup.log'"

echo
echo "확인 모드(운영 DB 를 안 건드린다)"
bash "$RESTORE" "$f" >"$WORK/verify.log" 2>&1 && ok "확인 모드 성공" || { bad "확인 모드 실패"; cat "$WORK/verify.log"; }
check "테이블 4개가 요약에 나온다" grep -q "테이블 4개" "$WORK/verify.log"
check "members 행 수가 원본과 같다($src_members)" grep -Eq "^ +members +$src_members\$" "$WORK/verify.log"
check "pose_data(파티션) 행 수가 원본과 같다($src_pose)" grep -Eq "^ +pose_data +$src_pose\$" "$WORK/verify.log"
check "Flyway 마지막 버전을 보여 준다" grep -q "V36 (add member consents)" "$WORK/verify.log"
check "임시 DB 가 지워졌다" test "$(q -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'restore_verify_%'")" = "0"
check "원본 DB 는 그대로다" test "$(q shadowfit -e 'SELECT COUNT(*) FROM members')" = "$src_members"

echo
echo "실제 복구(--into)"
bash "$RESTORE" "$f" --into restored >"$WORK/into.log" 2>&1 && ok "새 DB 로 복구 성공" || { bad "복구 실패"; cat "$WORK/into.log"; }
check "members 행 수가 같다" test "$(q restored -e 'SELECT COUNT(*) FROM members')" = "$src_members"
check "pose_data 행 수가 같다" test "$(q restored -e 'SELECT COUNT(*) FROM pose_data')" = "$src_pose"
check "audit(트리거가 만든 행) 수가 같다" test "$(q restored -e 'SELECT COUNT(*) FROM audit')" = "$src_audit"
check "한글·이모지가 그대로다" test "$(q restored -e "SELECT bio FROM members WHERE name='홍길동'")" = "스쿼트를 좋아함 💪"
check "따옴표가 든 문자열이 그대로다" test "$(q restored -e "SELECT bio FROM members WHERE name='Kim'")" = 'it'"'"'s a "quote"'
check "BLOB 이 그대로다" test "$(q restored -e "SELECT HEX(avatar) FROM members WHERE name='홍길동'")" = "00FF10AB"
check "파티션이 유지됐다(3개)" test "$(q restored -e "SELECT COUNT(*) FROM information_schema.partitions WHERE table_schema='restored' AND table_name='pose_data'")" = "3"
q restored -e "INSERT INTO members(name) VALUES ('after')" >/dev/null 2>&1 || true
check "복구된 DB 에서 트리거가 동작한다" test "$(q restored -e 'SELECT COUNT(*) FROM audit')" = "$((src_audit + 1))"
check "복구된 DB 에서 함수가 동작한다" test "$(q restored -e 'SELECT fn_double(21)')" = "42"

echo
echo "덮어쓰기 보호"
expect_fail "이미 테이블이 있는 DB 에는 --force 없이 거절" 1 bash "$RESTORE" "$f" --into restored
bash "$RESTORE" "$f" --into restored --force >/dev/null 2>&1 && ok "--force 로는 덮어쓴다" || bad "--force 복구 실패"
check "덮어쓴 뒤 행 수가 덤프 시점으로 돌아갔다" test "$(q restored -e 'SELECT COUNT(*) FROM members')" = "$src_members"

echo
echo "잘못된 입력"
expect_fail "없는 컨테이너면 백업이 실패한다" 1 env MYSQL_CONTAINER=no-such-container-xyz BACKUP_DIR="$WORK/b2" bash "$BACKUP"
check "실패한 백업이 파일을 남기지 않았다" test -z "$(ls "$WORK/b2"/*.sql.gz "$WORK/b2"/*.partial 2>/dev/null || true)"
size="$(wc -c < "$f" | tr -d ' ')"
head -c $((size / 2)) "$f" > "$WORK/truncated.sql.gz"
expect_fail "중간에 끊긴 덤프는 복구를 거절한다" 1 bash "$RESTORE" "$WORK/truncated.sql.gz"
printf 'not gzip' > "$WORK/garbage.sql.gz"
expect_fail "gzip 이 아닌 파일은 복구를 거절한다" 1 bash "$RESTORE" "$WORK/garbage.sql.gz"
expect_fail "존재하지 않는 파일은 거절한다" 1 bash "$RESTORE" "$WORK/nope.sql.gz"
expect_fail "BACKUP_KEEP 가 숫자가 아니면 거절한다" 1 env BACKUP_KEEP=abc BACKUP_DIR="$WORK/b3" bash "$BACKUP"
expect_fail "DB 이름에 이상한 문자가 있으면 거절한다" 1 env MYSQL_DATABASE='a;b' BACKUP_DIR="$WORK/b3" bash "$BACKUP"
expect_fail "--into 이름에 이상한 문자가 있으면 거절한다" 1 bash "$RESTORE" "$f" --into 'x;drop'

echo
echo "동시 실행 방지"
mkdir -p "$WORK/b4/.backup.lock"; echo $$ > "$WORK/b4/.backup.lock/pid"
expect_fail "살아 있는 프로세스가 잠금을 쥐고 있으면 실행하지 않는다" 2 env BACKUP_DIR="$WORK/b4" bash "$BACKUP"
echo 99999999 > "$WORK/b4/.backup.lock/pid"
BACKUP_DIR="$WORK/b4" bash "$BACKUP" >/dev/null 2>&1 && ok "주인이 죽은 잠금은 치우고 진행한다" || bad "죽은 잠금을 못 치웠다"

echo
echo "보관 개수 정리 (BACKUP_KEEP=2, 4회 실행)"
for _ in 1 2 3 4; do BACKUP_DIR="$WORK/b5" BACKUP_KEEP=2 bash "$BACKUP" >/dev/null 2>&1; sleep 1.2; done
check "2개만 남는다" test "$(ls -1 "$WORK/b5"/shadowfit-*.sql.gz | wc -l | tr -d ' ')" = "2"
newest="$(ls -1 "$WORK/b5"/shadowfit-*.sql.gz | sort | tail -1)"
check "남은 것 중 가장 최근 것이 있다" test -f "$newest"

echo
echo "S3 설정이 있는데 aws CLI 가 없으면"
if command -v aws >/dev/null 2>&1; then
  echo "  (건너뜀: 이 환경에는 aws CLI 가 있어 «없는 경우» 를 시험할 수 없다)"
else
  expect_fail "로컬 백업은 남기고 종료 코드 3 으로 알린다" 3 env BACKUP_DIR="$WORK/b6" BACKUP_S3_URI=s3://example/x bash "$BACKUP"
  check "로컬 백업은 남아 있다" test -n "$(ls "$WORK/b6"/shadowfit-*.sql.gz 2>/dev/null || true)"
fi

echo
echo "$PASS 통과, $FAIL 실패"
[ "$FAIL" -eq 0 ]
