#!/usr/bin/env bash
# ③ 락 실험 후속 — "만약 세션 완료도 비관락이었다면" 반사실 + findByIdForUpdate 락 범위 실물 확인
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ③ Ch.5 (a) 낙관락 양보 근거)
#
# ① 반사실: 세션 완료(FastAPI 콜백 vs 타임아웃 스케줄러)에 실제로는 @Version(낙관락)을 쓰는데,
#    만약 비관락(FOR UPDATE)을 썼다면 최악의 경우 몇 초 만에 lock wait timeout 에러가 나는지 재현.
# ② findByIdForUpdate(SessionService.createSession, 회원당 활성 세션 1개 제약)가 쓰는 PK 등치
#    FOR UPDATE가 실제로 레코드 락만 걸고 갭은 안 거는지 performance_schema.data_locks로 확인.
set -u
PW=1234
C=shadowfit-mysql
DB(){ docker exec $C mysql -uroot -p$PW shadowfit -N -e "$1" 2>/dev/null; }

MEMBER_ID=1
OUT=/tmp/lock_demo
mkdir -p "$OUT"

echo "=== ① lock wait timeout 반사실 ==="
docker exec $C mysql -uroot -p$PW shadowfit -e "
START TRANSACTION;
SELECT id, email FROM users WHERE id=$MEMBER_ID FOR UPDATE;
DO SLEEP(5);
COMMIT;
" > "$OUT/sessionA.log" 2>&1 &
PID_A=$!

sleep 0.5 # A가 확실히 락을 잡은 뒤 B 시작

T0=$(date +%s.%N)
docker exec $C mysql -uroot -p$PW shadowfit -e "
SET innodb_lock_wait_timeout=2;
START TRANSACTION;
SELECT id, email FROM users WHERE id=$MEMBER_ID FOR UPDATE;
COMMIT;
" > "$OUT/sessionB.log" 2>&1
T1=$(date +%s.%N)
ELAPSED=$(awk "BEGIN {printf \"%.3f\", $T1 - $T0}")

echo "B가 대기하다 겪은 결과:"; cat "$OUT/sessionB.log"
echo "B 소요 시간: ${ELAPSED}s (innodb_lock_wait_timeout=2 설정 — ~2초 근처에서 실패해야 정상)"
wait $PID_A

echo ""
echo "=== ② data_locks로 레코드 락(갭 없음) 실물 확인 ==="
docker exec $C mysql -uroot -p$PW shadowfit -e "
START TRANSACTION;
SELECT id, email FROM users WHERE id=$MEMBER_ID FOR UPDATE;
DO SLEEP(3);
COMMIT;
" > "$OUT/sessionC.log" 2>&1 &
PID_C=$!

sleep 0.5
docker exec $C mysql -uroot -p$PW shadowfit -e "
START TRANSACTION;
SELECT id, email FROM users WHERE id=$MEMBER_ID FOR UPDATE;
COMMIT;
" > "$OUT/sessionD.log" 2>&1 &
PID_D=$!

sleep 1 # C, D 둘 다 걸린 상태에서 스냅샷
DB "SELECT ENGINE_LOCK_ID, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
FROM performance_schema.data_locks
WHERE OBJECT_NAME='users' ORDER BY LOCK_STATUS;"

wait $PID_C $PID_D
echo "완료"
