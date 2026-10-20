-- 세트 평균 싱크로율 NULL 허용 — V28(횟수만 세는 종목)과 V26(세트 표)의 합류점.
--
-- V26 은 «rep 이 없는 세트는 생기지 않으므로 NOT NULL» 이라고 적었는데, 그 전제는 «모든 rep 에 sync_rate 가
-- 있다» 였다. V28 이 횟수만 세는 종목(exercises.repetition_only)을 들여오면서 pose_data.sync_rate 가 NULL 인
-- rep 이 생겼다 — 그 세션도 세트(횟수·시각)는 그대로 의미가 있으므로 행은 만들되, 평균은 0 이 아니라 NULL 로
-- 둔다. 0 을 쓰면 «미측정» 이 «싱크로율 0%» 로 둔갑한다(exercise_sessions.avg_sync_rate 와 같은 규약, #75).
ALTER TABLE exercise_session_sets
    MODIFY COLUMN avg_sync_rate DECIMAL(5,2) NULL COMMENT 'rep 가중 평균. NULL = 채점된 rep 이 없는 세트(횟수만 세는 종목)';
