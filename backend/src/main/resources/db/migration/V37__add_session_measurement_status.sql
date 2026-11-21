-- 세션의 «측정 상태» — 완료 통보(SessionCompleteRequest.measurement_status)에서 total_reps 가 0 인 **이유**를 받아 둔다.
--
-- NULL = «측정됨» 이거나 이 컬럼이 생기기 전 세션(또는 이 필드를 모르는 구버전 AI 가 완료시킨 세션).
-- 값이 있으면 측정된 것이 없는 세션이다:
--   NO_POSE            수락된 프레임이 하나도 없다 — 사람을 못 찾았거나 프레임이 오지 않았다
--   NOT_FULLY_VISIBLE  프레임은 받았지만 판정에 쓸 수 있는 것이 없었다(하체 등이 화면 밖)
--   NO_REP             판정된 프레임은 있으나 인정 반복·미인정 시도가 0
--
-- VARCHAR 다(ENUM 아님) — 사유가 늘 때 ALTER 없이 받는다(exercise_rejected_attempts.reason · member_consents.consent_type 과 같은 방침).
-- 허용 값 검증은 Spring 이 한다(model.exercise.MeasurementStatus). 기존 행은 건드리지 않는다 — 기본 NULL 이 «측정됨» 이다.
ALTER TABLE exercise_sessions
    ADD COLUMN measurement_status VARCHAR(30) NULL;
