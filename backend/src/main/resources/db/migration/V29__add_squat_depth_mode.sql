-- 스쿼트 깊이 모드(풀/하프) — 세션 시작 시 선택, 종목 선택과 같은 화면(exercise.tsx).
-- 스쿼트가 아닌 세션(런지 등)에는 의미가 없다 — 항상 채워져 있지만 그 종목의 리포트 계산은
-- 이 컬럼을 읽지 않는다(SquatDepthAnalysisCalculator의 exercise_id 가드 참고).
ALTER TABLE exercise_sessions
    ADD COLUMN squat_depth_mode VARCHAR(10) NOT NULL DEFAULT 'FULL';
