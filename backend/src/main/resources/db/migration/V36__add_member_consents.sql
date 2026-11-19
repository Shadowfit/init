-- 약관·개인정보 동의 기록 (docs/tasks/39 §A-1).
--
-- 한 행 = «이 회원이 이 종류의 문서를 이 버전으로 동의했다» 의 증거다. 문서가 개정돼 버전이 오르면 새 행이 쌓이고
-- (이전 행은 지우지 않는다 — 언제 어떤 버전에 동의했는지가 기록의 목적이다), «현재 버전에 동의했는가» 는 현재 버전의 행이
-- 있는지로 판정한다.
--
--   TERMS       이용약관                         (가입 필수)
--   PRIVACY     개인정보 수집·이용                (가입 필수)
--   HEALTH_INFO 건강정보(키·몸무게·운동 수준)      (온보딩에서 몸 정보를 받기 전 별도 동의 — 일반 동의와 분리)
--
-- consent_type 은 VARCHAR 다(ENUM 아님) — 종류가 늘 때 ALTER 없이 받는다. 허용 값 검증은 Spring 입구가 한다
-- (exercise_rejected_attempts.reason 과 같은 방침).
-- 같은 (회원, 종류, 버전) 은 한 번만 — 재전송·중복 호출이 행을 늘리지 않게 UNIQUE 로 막는다.
-- IP·기기 정보는 일부러 저장하지 않는다. 동의의 증거에 필요한 건 «누가·무엇을·언제» 이고, 더 모으면 그만큼이 새 개인정보다.
-- FK ON DELETE CASCADE — 탈퇴하면 같이 지운다(동의 기록도 개인정보다. 법정 보관 의무가 있는지는 법률 확인 항목).
CREATE TABLE member_consents (
    id            BIGINT      AUTO_INCREMENT PRIMARY KEY,
    member_id     BIGINT      NOT NULL,
    consent_type  VARCHAR(30) NOT NULL COMMENT 'TERMS | PRIVACY | HEALTH_INFO',
    version       VARCHAR(20) NOT NULL COMMENT '동의한 문서 버전 — 문서 개정 시 재동의 판단용',
    agreed_at     DATETIME    NOT NULL,
    UNIQUE KEY uk_member_consent (member_id, consent_type, version),
    CONSTRAINT fk_member_consents_member FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
) COMMENT = '약관·개인정보·건강정보 동의 기록 — 회원 × 종류 × 버전';
