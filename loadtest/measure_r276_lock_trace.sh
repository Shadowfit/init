#!/usr/bin/env bash
# #276 결정적 재현 — 두 세션을 한 문장씩 진행시키며 단계마다 performance_schema.data_locks 를 찍는다
#
# 이슈: https://github.com/Shadowfit/init/issues/276
# 지금까지의 판(r276-*)은 전부 «확률» 이었다 — 워커 N 개를 풀어놓고 데드락 비율을 셌다.
# 잠금 자리(PRIMARY supremum, 파티션 p2026_05)는 데드락 덤프 한 쌍으로만 봤고,
# **어느 문장·어느 시점에 그 X 가 생기는지**는 미검증으로 남아 있었다(08-20 코멘트).
# 이 rig 은 부하를 걸지 않는다. 세션 둘(t1·t2)에 문장을 하나씩 넣고, 넣을 때마다 제3의
# 세션(root)이 잠금 표를 읽는다. 같은 입력이면 같은 출력이 나오므로 판 수가 필요 없다.
#
# 무대: 격리 컨테이너(mysql:8.0) 에 Flyway V1~V26 을 그대로 적용한 스키마.
#   대상 표는 매 시나리오마다 `pose_lab` 을 `CREATE TABLE ... LIKE pose_data` 로 새로 만든다
#   (파티션·PK·uk_pose_event 전부 복제). 팔에 따라 키·격리수준·INSERT 형태만 바꾼다.
#
# 쓰는 법:
#   docker run -d --name r276-locktrace -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shadowfit mysql:8.0
#   (V*.sql 을 버전 순으로 적용 — README 참고)
#   CONTAINER=r276-locktrace ./loadtest/measure_r276_lock_trace.sh > out.txt
#
# ⚠️ 단계 사이 간격은 SLEEP(기본 1초)이다. 막힌 문장은 innodb_lock_wait_timeout(기본 50초)
#    안에 다음 단계가 풀어주므로 간격이 결과를 바꾸지 않는다 — 단 박스가 극단적으로 느리면
#    «막혔다» 와 «아직 안 끝났다» 를 구분 못 한다. 각 dump 의 LOCK_STATUS=WAITING 으로 판정한다.
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash 가 docker exec 인자의 /tmp 를 Windows 경로로 바꾸지 않게

CONTAINER=${CONTAINER:-r276-locktrace}
SLEEP=${SLEEP:-1}
ARMS=${ARMS:-"base dup_blocks_new plain_insert dup_only new_only no_uk insert_ignore read_committed natural_pk natural_pk_keep_id"}

# 컨테이너 안에서 도는 드라이버. 인자: 시나리오 파일 경로(컨테이너 안).
# 시나리오 줄 형식:  T1|sql  ·  T2|sql  ·  ROOT|sql  ·  DUMP|라벨
read -r -d '' DRIVER <<'EOS'
set -u
SCEN=$1; SLEEP=$2
cd /tmp && rm -f p1 p2 t1.log t2.log && mkfifo p1 p2
# -n: 버퍼링 끔 · --force: 데드락 뒤에도 세션을 살려 둔다 · -vvv: 문장과 결과를 같이 남긴다
mysql -ut1 -pp1 -n --force -vvv shadowfit <p1 >t1.log 2>&1 &
mysql -ut2 -pp2 -n --force -vvv shadowfit <p2 >t2.log 2>&1 &
exec 3>p1 4>p2
Q="SELECT t.PROCESSLIST_USER AS who, l.INDEX_NAME AS idx, l.PARTITION_NAME AS part,
          l.LOCK_TYPE AS type, l.LOCK_MODE AS mode, l.LOCK_STATUS AS status, l.LOCK_DATA AS data
     FROM performance_schema.data_locks l
     JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
    WHERE l.OBJECT_NAME = 'pose_lab'
    ORDER BY who, l.LOCK_TYPE, idx, l.ENGINE_LOCK_ID;"
while IFS='|' read -r who sql; do
  [ -z "$who" ] && continue
  case "$who" in
    \#*) ;;
    T1) echo "$sql" >&3; echo ">>> T1: $sql"; sleep "$SLEEP" ;;
    T2) echo "$sql" >&4; echo ">>> T2: $sql"; sleep "$SLEEP" ;;
    ROOT) mysql -uroot -proot shadowfit -e "$sql" 2>&1 | grep -v 'Using a password' ;;
    DUMP) echo "=== [$sql]"; mysql -uroot -proot -t -e "$Q" 2>&1 | grep -v 'Using a password' || true
          echo "    (잠금 없음이면 표가 비어 있다)" ;;
  esac
done < "$SCEN"
exec 3>&- 4>&-
wait
echo "--- t1 세션 로그 (오류만)"; grep -E 'ERROR|Query OK|rows affected' t1.log | sed 's/^/    /'
echo "--- t2 세션 로그 (오류만)"; grep -E 'ERROR|Query OK|rows affected' t2.log | sed 's/^/    /'
EOS

v() { echo "($1, $2, $3.000, JSON_OBJECT('k',1), 50.00, 0.00, NULL, '2026-05-15 10:00:00')"; }
COLS="(session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message, created_at)"
ODKU="ON DUPLICATE KEY UPDATE session_id = session_id"   # PoseDataService.INSERT_POSE_SQL 과 같은 꼴
ins()  { echo "INSERT INTO pose_lab $COLS VALUES $(v "$1" "$2" "$3") $ODKU;"; }
insi() { echo "INSERT IGNORE INTO pose_lab $COLS VALUES $(v "$1" "$2" "$3");"; }

# 공통 준비: 원본(재전송이 겹칠 대상) 두 행을 커밋해 둔다 — 세션 901·902 의 rep1.
setup() {  # $1 = 표 변형 DDL(없으면 빈 문자열)
  cat <<EOF
ROOT|DROP TABLE IF EXISTS pose_lab; CREATE TABLE pose_lab LIKE pose_data; $1
ROOT|INSERT INTO pose_lab $COLS VALUES $(v 901 1 1), $(v 902 1 1);
EOF
}

# 기본 순서: 둘 다 «중복» 을 먼저 넣고, 그다음 둘 다 «신규» 를 넣는다.
# = 재전송 배치(앞 rep 은 원본과 겹치고 뒤 rep 은 새로 온다)가 두 세션에서 동시에 도는 모양.
body_dup_then_new() {  # $1 = 삽입 함수(ins|insi) · $2 = 세션 준비문
  local f=$1
  cat <<EOF
T1|$2 BEGIN;
T2|$2 BEGIN;
DUMP|0 시작 — 둘 다 BEGIN
T1|$($f 901 1 1)
DUMP|1 T1 이 중복(901,1,1) 을 넣은 직후
T2|$($f 902 1 1)
DUMP|2 T2 가 중복(902,1,1) 을 넣은 직후
T1|$($f 901 2 2)
DUMP|3 T1 이 신규(901,2,2) 를 넣은 직후
T2|$($f 902 2 2)
DUMP|4 T2 가 신규(902,2,2) 를 넣은 직후
T1|COMMIT;
T2|COMMIT;
DUMP|5 둘 다 COMMIT 뒤
ROOT|SELECT session_id, rep_number, timestamp_sec, id FROM pose_lab ORDER BY session_id, rep_number;
EOF
}

scenario() {
  case "$1" in
    base)           setup "";                                  body_dup_then_new ins "" ;;
    insert_ignore)  setup "";                                  body_dup_then_new insi "" ;;
    read_committed) setup "";                                  body_dup_then_new ins "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;" ;;
    no_uk)          setup "ALTER TABLE pose_lab DROP INDEX uk_pose_event;"; body_dup_then_new ins "" ;;
    # 자연키 PK: auto-increment id 를 없애고 멱등 키를 곧 PK 로 — 파티션 제약상 created_at 포함
    # (AUTO_INCREMENT 를 먼저 떼야 PK 를 내릴 수 있고, 한 ALTER 안에서 MODIFY 와 DROP COLUMN 을 같이 못 쓴다)
    natural_pk)     setup "ALTER TABLE pose_lab MODIFY id BIGINT NOT NULL; ALTER TABLE pose_lab DROP INDEX uk_pose_event, DROP PRIMARY KEY, DROP COLUMN id, ADD PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at);"
                    body_dup_then_new ins "" | sed 's/, id FROM/ FROM/' ;;
    # 자연키 PK + id 를 AUTO_INCREMENT 보조 인덱스로 남긴다 — reports.detailed_analysis 가 poseDataId 를
    # 영구 저장하므로(WorstSectionDto) id 를 지우면 기존 리포트 참조가 깨진다. 보조 인덱스 끝에서 같은 일이 나는가
    natural_pk_keep_id) setup "ALTER TABLE pose_lab DROP INDEX uk_pose_event, DROP PRIMARY KEY, ADD PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at), ADD KEY idx_pose_id (id);"
                    body_dup_then_new ins "" ;;
    dup_only)       setup ""
                    cat <<EOF
ROOT|INSERT INTO pose_lab $COLS VALUES $(v 901 2 2), $(v 902 2 2);
T1|BEGIN;
T2|BEGIN;
T1|$(ins 901 1 1)
DUMP|1 T1 중복
T2|$(ins 902 1 1)
DUMP|2 T2 중복
T1|$(ins 901 2 2)
DUMP|3 T1 중복(두 번째)
T2|$(ins 902 2 2)
DUMP|4 T2 중복(두 번째)
T1|COMMIT;
T2|COMMIT;
EOF
                    ;;
    # 중복 하나가 «남의 신규 삽입» 까지 막는가 — T2 는 원본과 무관한 새 세션(903)의 새 키
    dup_blocks_new) setup ""
                    cat <<EOF
T1|BEGIN;
T2|BEGIN;
T1|$(ins 901 1 1)
DUMP|1 T1 중복
T2|$(ins 903 1 1)
DUMP|2 T2 가 무관한 신규(903,1,1) 를 넣은 직후
T1|COMMIT;
DUMP|3 T1 COMMIT 뒤
T2|COMMIT;
EOF
                    ;;
    # ODKU 가 아닌 평범한 INSERT 가 중복으로 실패해도(1062) 같은 락이 남는가 — ODKU 고유인지 가른다
    plain_insert)   setup ""
                    cat <<EOF
T1|BEGIN;
T1|INSERT INTO pose_lab $COLS VALUES $(v 901 1 1);
DUMP|1 T1 의 평범한 INSERT 가 1062 로 실패한 직후
T1|ROLLBACK;
EOF
                    ;;
    new_only)       setup ""
                    cat <<EOF
T1|BEGIN;
T2|BEGIN;
T1|$(ins 901 3 3)
DUMP|1 T1 신규
T2|$(ins 902 3 3)
DUMP|2 T2 신규
T1|$(ins 901 4 4)
DUMP|3 T1 신규(두 번째)
T2|$(ins 902 4 4)
DUMP|4 T2 신규(두 번째)
T1|COMMIT;
T2|COMMIT;
EOF
                    ;;
  esac
}

docker exec "$CONTAINER" mysql -uroot -proot -e "
  CREATE USER IF NOT EXISTS t1 IDENTIFIED BY 'p1'; CREATE USER IF NOT EXISTS t2 IDENTIFIED BY 'p2';
  GRANT ALL ON shadowfit.* TO t1, t2;" 2>/dev/null
echo "# MySQL $(docker exec "$CONTAINER" mysql -uroot -proot -N -e 'select version()' 2>/dev/null) · 기본 격리 $(docker exec "$CONTAINER" mysql -uroot -proot -N -e 'select @@transaction_isolation' 2>/dev/null) · SLEEP=$SLEEP"
docker exec -i "$CONTAINER" bash -c 'cat > /tmp/driver.sh' <<<"$DRIVER"
dl() { docker exec "$CONTAINER" mysql -uroot -proot -N -e "SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'" 2>/dev/null; }
for arm in $ARMS; do
  echo; echo "################ 팔: $arm ################"
  scenario "$arm" | docker exec -i "$CONTAINER" bash -c 'cat > /tmp/scen.txt'
  before=$(dl)
  docker exec "$CONTAINER" bash /tmp/driver.sh /tmp/scen.txt "$SLEEP"
  after=$(dl)
  echo "--- 이 팔의 데드락 수: $((after - before))"
  # LATEST DETECTED DEADLOCK 은 서버 수명 동안 남으므로, 이 팔에서 새로 났을 때만 찍는다
  [ "$after" -gt "$before" ] && docker exec "$CONTAINER" mysql -uroot -proot -e "SHOW ENGINE INNODB STATUS\G" 2>/dev/null \
    | sed -n '/LATEST DETECTED DEADLOCK/,/^TRANSACTIONS$/p' | grep -E 'TRANSACTION|index|lock_mode|lock mode|WE ROLL BACK|supremum|INSERT' | head -30 \
    | sed 's/^/    [deadlock] /'
done
