-- 체간(몸통) 기울기 점수(TrunkLeanAnalysisCalculator, 2026-09-28)용. 어깨 중점-엉덩이 중점
-- 벡터가 수직에서 벗어난 각도(도) — 스쿼트·런지 공용. leadLeg(V24)와 달리 0이 "완전 직립"이라는
-- 유효한 값이라 NULL은 순수하게 "구버전 행이라 이 컬럼 자체가 없었다"만 뜻한다.
ALTER TABLE pose_data
    ADD COLUMN torso_tilt_deg DOUBLE NULL;
