-- 전방 런지는 횟수만 측정한다. AI 런지 분석기를 먼저 배포한 뒤 적용한다.
-- 미측정 점수를 0점으로 저장하면 리포트 평균을 오염시키므로 NULL을 허용한다.
ALTER TABLE pose_data MODIFY COLUMN sync_rate DECIMAL(5,2) NULL;
ALTER TABLE exercises ADD COLUMN repetition_only BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE exercises SET analysis_supported = TRUE, repetition_only = TRUE WHERE id = 2;
