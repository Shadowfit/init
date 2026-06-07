#!/usr/bin/env bash
# ③ MVCC 스냅샷 실험 — 긴 적재 트랜잭션 ↔ 동시 리포트 조회 일관성
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ④ Ch.4)
#
# 타깃: gRPC 배치 INSERT(pose_data 적재)가 한 트랜잭션으로 길게 열려 있는 동안,
#   동시에 리포트 조회가 들어오면? InnoDB MVCC 의 일관된 읽기(consistent read)로
#   - 읽기는 쓰기를 블로킹하지 않고(락 안 잡음), 쓰기도 읽기를 블로킹하지 않는다.
#   - REPEATABLE READ: 트랜잭션 첫 읽기 시점의 스냅샷을 끝까지 본다(중간에 커밋된 INSERT 안 보임).
#   - READ COMMITTED: 매 SELECT 마다 새 스냅샷(커밋된 INSERT 보임).
# 가설: ① 적재 트랜잭션이 열려 있어도 리포트 조회는 대기 없이 즉시 일관된 결과를 받는다.
#       ② 같은 트랜잭션 안에서 RR 은 옛 카운트 고정, RC 는 새 카운트로 바뀐다.
# 핵심: scratch mvcc_lab(소규모, 값 내용 무관 — 행수/가시성만 관찰).
set -u
PW=1234
C=shadowfit-mysql
DB(){ docker exec $C mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }
SESS(){ docker exec -i $C mysql -N -uroot -p$PW shadowfit 2>/dev/null; }  # -N: 컬럼헤더 억제(CONCAT 라벨만 출력)

reset_lab(){
  DB -e "DROP TABLE IF EXISTS mvcc_lab;
         CREATE TABLE mvcc_lab(id INT AUTO_INCREMENT PRIMARY KEY, v INT NOT NULL);
         INSERT INTO mvcc_lab(v) SELECT 1 FROM information_schema.columns LIMIT 100;"
}
cnt(){ DB -N -e "SELECT COUNT(*) FROM mvcc_lab"; }

echo "## [0] scratch mvcc_lab — 초기 100 행 (pose_data 적재↔조회 모사)"
reset_lab
echo "초기 행수: $(cnt)"

echo
echo "## [1] REPEATABLE READ — 적재 트랜잭션 중 동시 조회는 '첫 스냅샷' 고정"
echo "Reader(RR): BEGIN→SELECT(스냅샷 확정)→대기→재SELECT→COMMIT→재SELECT."
echo "그 대기 동안 Writer 가 50행 INSERT+COMMIT. Reader 의 트랜잭션 내 카운트는 안 변해야 함."
reset_lab
# Reader: 결과를 라벨과 함께 출력. 한 커넥션(=한 트랜잭션) 유지.
( SESS <<'SQL'
SET SESSION transaction_isolation='REPEATABLE-READ';
BEGIN;
SELECT CONCAT('  R1 트랜잭션 첫 읽기(스냅샷 확정): ', COUNT(*)) FROM mvcc_lab;
DO SLEEP(3);
SELECT CONCAT('  R2 (Writer 50행 커밋 이후, 같은 트랜잭션): ', COUNT(*)) FROM mvcc_lab;
COMMIT;
SELECT CONCAT('  R3 (COMMIT 후 새 트랜잭션): ', COUNT(*)) FROM mvcc_lab;
SQL
) &
sleep 1
# Writer: Reader 스냅샷 확정 후 50행 추가 커밋. 블로킹 없이 즉시 끝나야 함.
W_START=$(date +%s%N)
SESS <<'SQL'
INSERT INTO mvcc_lab(v) SELECT 2 FROM information_schema.columns LIMIT 50;
SQL
W_END=$(date +%s%N)
echo "  W  Writer INSERT 50+COMMIT 소요: $(( (W_END-W_START)/1000000 )) ms (Reader 가 안 막음 → 작아야 함)"
wait
echo "  기대: R1=100, R2=100(스냅샷 고정), R3=150(새 트랜잭션) → RR 일관된 읽기 입증"

echo
echo "## [2] READ COMMITTED — 같은 시나리오, 매 SELECT 새 스냅샷"
echo "Reader(RC): 트랜잭션 중이라도 Writer 커밋분이 두번째 SELECT 에 보인다."
reset_lab
( SESS <<'SQL'
SET SESSION transaction_isolation='READ-COMMITTED';
BEGIN;
SELECT CONCAT('  R1 트랜잭션 첫 읽기: ', COUNT(*)) FROM mvcc_lab;
DO SLEEP(3);
SELECT CONCAT('  R2 (Writer 50행 커밋 이후, 같은 트랜잭션): ', COUNT(*)) FROM mvcc_lab;
COMMIT;
SQL
) &
sleep 1
SESS <<'SQL'
INSERT INTO mvcc_lab(v) SELECT 3 FROM information_schema.columns LIMIT 50;
SQL
wait
echo "  기대: R1=100, R2=150 → RC 는 매 문장 새 스냅샷(phantom 가능). RR 과의 대비가 핵심."

echo
echo "## [3] 읽기↔쓰기 비블로킹 동시 관찰 (data_locks — 일반 SELECT 는 락 0)"
echo "RR Reader 가 트랜잭션 열고 SELECT 중인 순간, mvcc_lab 에 잡힌 락을 본다."
reset_lab
( SESS <<'SQL'
SET SESSION transaction_isolation='REPEATABLE-READ';
BEGIN;
SELECT COUNT(*) INTO @c FROM mvcc_lab;
DO SLEEP(3);
COMMIT;
SQL
) &
sleep 1
echo "### [3-lock] Reader 스냅샷 읽기 중 data_locks (일반 SELECT 는 락을 안 잡음)"
DB -t -e "SELECT ENGINE_TRANSACTION_ID trx, LOCK_TYPE, LOCK_MODE, LOCK_STATUS
          FROM performance_schema.data_locks WHERE OBJECT_NAME='mvcc_lab';"
echo "  (행 0 = 일관된 읽기는 undo 로 과거 버전을 재구성할 뿐 락을 안 걸어 → 쓰기를 블로킹하지 않음)"
wait

echo
echo "## [4] 적재↔조회 서사 (실코드 매핑)"
echo "  gRPC SavePoseDataBatch 가 한 트랜잭션으로 N행 INSERT 중이어도,"
echo "  리포트 조회(ReportService)는 MVCC 스냅샷으로 대기 없이 '적재 시작 시점' 일관 뷰를 본다."
echo "  → 적재와 조회가 서로를 막지 않는다(OLTP 동시성). RR 기본이라 조회 일관성 보장."

echo
echo "DONE  (실측치는 docs/portfolio/realmysql-experiments.md §4 ④ '결과 (MVCC)' 에 박제)"
