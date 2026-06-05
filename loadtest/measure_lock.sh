#!/usr/bin/env bash
# ③ 락 실험 — lost-update 재현 + 3가지 방지(원자UPDATE / 비관락 / 낙관락 CAS)
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ③ Ch.5)
#
# 타깃: DailyLog.updateStats() = read-modify-write.
#   "세션 종료 시 그날 누적 운동시간 합산"을 배선하면, 같은 사용자가 같은 날
#   두 세션을 동시 종료할 때 두 트랜잭션이 같은 값을 읽고 덮어써 갱신이 유실된다.
# 가설: ① READ COMMITTED 에선 read-modify-write 가 lost-update 를 못 막는다(최종값 < 기댓값).
#       ② 원자 UPDATE(SET x=x+v) / SELECT..FOR UPDATE / @Version CAS 셋 다 손실을 막지만
#          락 비용 프로파일이 다르다 — 원자/낙관은 블로킹 없음, 비관은 X 레코드락으로 직렬화.
# 핵심: 실데이터(daily_logs) 대신 동형 scratch 테이블 lock_lab 으로 격리·매 run 초기화.
set -u
PW=1234
C=shadowfit-mysql
DB(){ docker exec $C mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }
# 트랜잭션 세션 1개 = heredoc 1번 호출(=커넥션 1개). 백그라운드(&)로 동시 실행.
SESS(){ docker exec -i $C mysql -uroot -p$PW shadowfit 2>/dev/null; }

A_ADD=10   # 트랜잭션 A 가 더할 값(세션 A 운동시간)
B_ADD=20   # 트랜잭션 B 가 더할 값
EXPECT=$((A_ADD + B_ADD))   # 둘 다 반영되면 30

reset_lab(){
  DB -e "CREATE TABLE IF NOT EXISTS lock_lab(
            id INT PRIMARY KEY,
            total_exercise_time INT NOT NULL DEFAULT 0,
            version BIGINT NOT NULL DEFAULT 0);
         REPLACE INTO lock_lab(id,total_exercise_time,version) VALUES (1,0,0);"
}
final(){ DB -N -e "SELECT total_exercise_time FROM lock_lab WHERE id=1"; }
verdict(){ # $1 = 최종값
  if [ "$1" -eq "$EXPECT" ]; then echo "  → 최종 $1 == 기댓값 $EXPECT  ✅ 손실 없음";
  else echo "  → 최종 $1 != 기댓값 $EXPECT  ❌ 갱신 손실 $((EXPECT-$1)) 만큼 유실"; fi
}

echo "## [0] scratch 테이블 lock_lab (daily_logs.updateStats read-modify-write 모사)"
reset_lab
echo "초기값: $(final), A=+$A_ADD, B=+$B_ADD, 기댓값=$EXPECT"

echo
echo "## [1] ❌ 재현 — READ COMMITTED + naive read-modify-write"
echo "두 커넥션이 동시에 SELECT(=0) → SLEEP → UPDATE=@x+add. 둘 다 0 을 읽고 덮어씀."
reset_lab
SESS <<SQL &
SET SESSION transaction_isolation='READ-COMMITTED';
SELECT total_exercise_time INTO @x FROM lock_lab WHERE id=1;  -- 둘 다 0 읽음
DO SLEEP(2);
UPDATE lock_lab SET total_exercise_time=@x+$A_ADD WHERE id=1;
SQL
SESS <<SQL &
SET SESSION transaction_isolation='READ-COMMITTED';
SELECT total_exercise_time INTO @x FROM lock_lab WHERE id=1;
DO SLEEP(2);
UPDATE lock_lab SET total_exercise_time=@x+$B_ADD WHERE id=1;
SQL
wait
v=$(final); verdict "$v"

echo
echo "## [2] ✅ 방지 A — 원자 UPDATE (SET x = x + v, 앱 변수 안 거침)"
echo "각 UPDATE 가 행에 X락 → 두번째가 첫번째 commit 까지 대기 → 직렬화. 블로킹은 UPDATE 한 문장뿐."
reset_lab
SESS <<SQL &
UPDATE lock_lab SET total_exercise_time = total_exercise_time + $A_ADD WHERE id=1;
SQL
SESS <<SQL &
UPDATE lock_lab SET total_exercise_time = total_exercise_time + $B_ADD WHERE id=1;
SQL
wait
v=$(final); verdict "$v"

echo
echo "## [3] ✅ 방지 B — 비관락 (SELECT ... FOR UPDATE)"
echo "A 가 FOR UPDATE 로 X락 잡고 트랜잭션 내 SLEEP(2). B 의 FOR UPDATE 는 그동안 블로킹."
reset_lab
# A: 락 잡고 2초 점유 후 commit
SESS <<SQL &
BEGIN;
SELECT total_exercise_time INTO @x FROM lock_lab WHERE id=1 FOR UPDATE;
DO SLEEP(2);
UPDATE lock_lab SET total_exercise_time=@x+$A_ADD WHERE id=1;
COMMIT;
SQL
sleep 0.6
# B: A 가 락 점유 중 → FOR UPDATE 에서 대기. 대기하는 동안 락 관찰(아래 [3-lock]).
SESS <<SQL &
BEGIN;
SELECT total_exercise_time INTO @x FROM lock_lab WHERE id=1 FOR UPDATE;  -- 여기서 블로킹
UPDATE lock_lab SET total_exercise_time=@x+$B_ADD WHERE id=1;
COMMIT;
SQL
sleep 0.6
echo "### [3-lock] B 가 대기 중인 순간 — performance_schema.data_locks / data_lock_waits"
DB -t -e "SELECT ENGINE_TRANSACTION_ID AS trx, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
          FROM performance_schema.data_locks WHERE OBJECT_NAME='lock_lab';"
DB -t -e "SELECT r.TRX_ID AS waiting_trx, r.TRX_MYSQL_THREAD_ID AS waiting_thread,
                 b.TRX_ID AS blocking_trx, b.TRX_MYSQL_THREAD_ID AS blocking_thread,
                 r.TRX_QUERY AS waiting_query
          FROM information_schema.innodb_lock_waits w
          JOIN information_schema.innodb_trx b ON b.TRX_ID = w.BLOCKING_TRX_ID
          JOIN information_schema.innodb_trx r ON r.TRX_ID = w.REQUESTING_TRX_ID;" 2>/dev/null \
  || DB -t -e "SELECT * FROM performance_schema.data_lock_waits\G"
wait
v=$(final); verdict "$v"
echo "  (B 는 A commit 후 깨어나 30 을 읽고 +20? 아니라 A=10 반영분 위에 재읽기 → 30. 블로킹 대가로 정합성)"

echo
echo "## [4] ✅ 방지 C — 낙관락 @Version CAS (Session.java completeSession 과 동형)"
echo "UPDATE ... SET version=version+1 WHERE id=1 AND version=@v. affected=0 이면 재시도."
reset_lab
# 낙관락 한 트랜잭션을 bash 재시도 루프로 모사. 두 worker 가 동시에 +값 누적.
optimistic_worker(){
  local add=$1 max=5 attempt
  for attempt in $(seq 1 $max); do
    # 현재 (total, version) 읽기
    read cur ver < <(DB -N -e "SELECT total_exercise_time, version FROM lock_lab WHERE id=1")
    # CAS: version 이 그대로일 때만 성공
    local rc
    rc=$(DB -N -e "UPDATE lock_lab SET total_exercise_time=$((cur+add)), version=$((ver+1))
                   WHERE id=1 AND version=$ver; SELECT ROW_COUNT();")
    if [ "$rc" = "1" ]; then
      echo "  worker(+$add): attempt $attempt 성공 (version $ver→$((ver+1)))"
      return 0
    fi
    echo "  worker(+$add): attempt $attempt 충돌(version 바뀜) → 재시도"
  done
  echo "  worker(+$add): $max 회 모두 충돌 — 포기"; return 1
}
optimistic_worker $A_ADD &
optimistic_worker $B_ADD &
wait
v=$(final); verdict "$v"

echo
echo "## [5] SHOW ENGINE INNODB STATUS — 최근 락/트랜잭션 요약 (TRANSACTIONS 섹션 일부)"
DB -e "SHOW ENGINE INNODB STATUS\G" | sed -n '/TRANSACTIONS/,/FILE I\/O/p' | head -30

echo
echo "DONE  (실측치는 docs/portfolio/realmysql-experiments.md §4 ③ '결과' 에 박제)"
