-- 대용량 실험용 합성 세션 시딩 (RealMySQL 실험 선결)
-- DAU 1,000 시나리오의 부분 시뮬레이션 (디스크 제약상 수천만 행 규모).
-- reference_source='seed' 로 태깅 → 실데이터(601 등)와 구분, pose 시딩/정리에 사용.
--
-- 5,000 세션을 2026년 전체에 ~균등 분산 (월 단위 파티션·pruning 실험용).
-- digits cross join 으로 0..9999 생성 (recursive CTE 한도 회피).

INSERT INTO exercise_sessions
    (member_id, exercise_id, reference_source, start_time, end_time,
     total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, created_at)
SELECT
    1 + (n % 3)                                   AS member_id,     -- 멤버 1..3 순환
    1 + (n % 2)                                   AS exercise_id,   -- 운동 1..2 순환
    'seed'                                        AS reference_source,
    ts                                            AS start_time,
    ts + INTERVAL 15 MINUTE                       AS end_time,
    30, 75.00, 95.00, 50.00,                                       -- 집계 더미
    'COMPLETED', 0, ts
FROM (
    SELECT n, TIMESTAMP('2026-01-01 06:00:00') + INTERVAL (n * 105) MINUTE AS ts
    FROM (
        SELECT d0.n + d1.n*10 + d2.n*100 + d3.n*1000 AS n
        FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d0
        CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
        CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
        CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
    ) digits
    WHERE n < 5000
) seq;
