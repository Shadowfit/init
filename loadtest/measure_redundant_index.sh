#!/usr/bin/env bash
# 중복 인덱스 후보 검증 — session_feedback_logs
# (docs/decisions/production-signal-checklist.md:343, 2026-07-24)
#
# 가설: idx_session_feedback(session_id, occurred_at) 는 uk_session_event(session_id, occurred_at,
#       feedback_type) 의 선두 2컬럼과 겹쳐 읽기 쪽엔 이득이 없고, batch INSERT마다 B+tree 유지비용만
#       이중으로 든다. 실제 쓰이는 쿼리 2종(findBySessionIdOrderByOccurredAtAsc, GROUP BY feedback_type
#       집계)의 EXPLAIN이 idx_session_feedback 유무와 무관하게 동일한지, 그리고 batch INSERT 비용에
#       측정 가능한 차이가 있는지 스크래치 테이블(session_feedback_logs_scale)로 검증한다.
#
# ⚠️ 로컬 2코어(i3-6100) + MySQL·백엔드 동거 환경 — 절대 ms 수치는 신뢰 금지, 메커니즘·상대 델타만.
set -u
PW=1234
DB(){ docker exec shadowfit-mysql mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }

SESSIONS=50000     # 세션 수
PER=20             # 세션당 평균 이벤트 수 (20 * 50,000 = 1,000,000 행)
INSERT_SESSIONS=5000  # 이후 batch INSERT 비교에 쓸 신규 세션 수 (5,000*20=100,000 행)

echo "## [1/6] 스크래치 테이블 생성 (인덱스 없이 — 시딩 가속)"
DB -e "
DROP TABLE IF EXISTS session_feedback_logs_scale;
CREATE TABLE session_feedback_logs_scale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  feedback_type VARCHAR(30) NOT NULL,
  sync_rate_at_trigger DECIMAL(5,2),
  occurred_at DATETIME NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS _seq;
CREATE TABLE _seq (n INT PRIMARY KEY);
INSERT INTO _seq
WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
SELECT d0.n+d1.n*10+d2.n*100+d3.n*1000+d4.n*10000 FROM d d0,d d1,d d2,d d3,d d4;"

echo "## [2/6] 시딩 — ${SESSIONS} 세션 × ${PER}행 (session_id, occurred_at 조합은 r.n 오프셋으로 유니크 보장)"
DB -e "
INSERT INTO session_feedback_logs_scale (session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at)
SELECT s.n+1,
       ELT(1+MOD(r.n,5), 'KNEE_OUT','BACK_BENT','HIP_HIGH','KNEE_IN','GOOD_FORM'),
       ROUND(50 + MOD(s.n*7+r.n*3, 45), 2),
       TIMESTAMP('2026-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND,
       TIMESTAMP('2026-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND
FROM _seq s CROSS JOIN _seq r WHERE s.n < $SESSIONS AND r.n < $PER;"

echo "## [3/6] 인덱스 3종 일괄 빌드 (실 schema.sql과 동일 구성)"
DB -e "
ALTER TABLE session_feedback_logs_scale
  ADD INDEX idx_session_feedback (session_id, occurred_at),
  ADD UNIQUE KEY uk_session_event (session_id, occurred_at, feedback_type);
ANALYZE TABLE session_feedback_logs_scale;"
DB -t -e "SELECT COUNT(*) total_rows, COUNT(DISTINCT session_id) sessions FROM session_feedback_logs_scale;"

echo
echo "## [4/6] EXPLAIN — idx_session_feedback 존재 상태 (3-index)"
echo "### (a) findBySessionIdOrderByOccurredAtAsc 패턴"
DB -e "EXPLAIN SELECT * FROM session_feedback_logs_scale WHERE session_id=100 ORDER BY occurred_at ASC\G" | grep -E 'key:|key_len:|rows:|Extra:'
echo "### (b) GROUP BY feedback_type 집계 패턴"
DB -e "EXPLAIN SELECT feedback_type, COUNT(*) FROM session_feedback_logs_scale WHERE session_id=100 GROUP BY feedback_type\G" | grep -E 'key:|key_len:|rows:|Extra:'

echo
echo "## [5/6] batch INSERT 비용 — 3-index 상태로 신규 ${INSERT_SESSIONS}세션×${PER}행 적재"
t0=$(date +%s%3N)
DB -e "
INSERT INTO session_feedback_logs_scale (session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at)
SELECT s.n+1+1000000,
       ELT(1+MOD(r.n,5), 'KNEE_OUT','BACK_BENT','HIP_HIGH','KNEE_IN','GOOD_FORM'),
       ROUND(50 + MOD(s.n*7+r.n*3, 45), 2),
       TIMESTAMP('2027-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND,
       TIMESTAMP('2027-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND
FROM _seq s CROSS JOIN _seq r WHERE s.n < $INSERT_SESSIONS AND r.n < $PER;"
t1=$(date +%s%3N)
echo "3-index INSERT (${INSERT_SESSIONS}*${PER}행): $((t1-t0)) ms"

echo
echo "## idx_session_feedback DROP"
DB -e "ALTER TABLE session_feedback_logs_scale DROP INDEX idx_session_feedback;"

echo
echo "## [6/6] EXPLAIN — idx_session_feedback DROP 후 (2-index, uk_session_event만 남음)"
echo "### (a) findBySessionIdOrderByOccurredAtAsc 패턴"
DB -e "EXPLAIN SELECT * FROM session_feedback_logs_scale WHERE session_id=100 ORDER BY occurred_at ASC\G" | grep -E 'key:|key_len:|rows:|Extra:'
echo "### (b) GROUP BY feedback_type 집계 패턴"
DB -e "EXPLAIN SELECT feedback_type, COUNT(*) FROM session_feedback_logs_scale WHERE session_id=100 GROUP BY feedback_type\G" | grep -E 'key:|key_len:|rows:|Extra:'

echo
echo "## batch INSERT 비용 — 2-index(uk_session_event만) 상태로 동일 규모 신규 세션 적재"
t0=$(date +%s%3N)
DB -e "
INSERT INTO session_feedback_logs_scale (session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at)
SELECT s.n+1+2000000,
       ELT(1+MOD(r.n,5), 'KNEE_OUT','BACK_BENT','HIP_HIGH','KNEE_IN','GOOD_FORM'),
       ROUND(50 + MOD(s.n*7+r.n*3, 45), 2),
       TIMESTAMP('2028-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND,
       TIMESTAMP('2028-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND
FROM _seq s CROSS JOIN _seq r WHERE s.n < $INSERT_SESSIONS AND r.n < $PER;"
t1=$(date +%s%3N)
echo "2-index INSERT (${INSERT_SESSIONS}*${PER}행): $((t1-t0)) ms"

echo
echo "## 정리"
DB -e "DROP TABLE IF EXISTS session_feedback_logs_scale; DROP TABLE IF EXISTS _seq;"
echo "DONE"
