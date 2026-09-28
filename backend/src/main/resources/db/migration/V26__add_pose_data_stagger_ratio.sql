-- 런지 보폭 점수(LungeStepLengthAnalysisCalculator, 2026-09-28)용. 두 발목 사이 거리 /
-- 어깨-엉덩이 길이 — 런지 전용이라 스쿼트 행은 항상 0(보폭 개념 없음)이지만, 그 계산기는
-- 런지 세션만 채점하므로 소비되지 않는다. NULL은 순수하게 "구버전 행이라 이 컬럼 자체가
-- 없었다"만 뜻한다.
ALTER TABLE pose_data
    ADD COLUMN stagger_ratio DOUBLE NULL;
