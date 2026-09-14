-- 주간 리포트 LLM 문장 저장 + 아웃박스 주간 리포트 차선 인덱스
-- docs/decisions/report-generation-llm.md §14-1 A-a(새 표) · §5-2 안 A(별도 발행기) — 2026-09-14 사용자 결정
--
-- 왜 새 표인가: V16 이 reports → session_reports 로 이름을 바꾸며 «세션 전용» 을 못박았다. 08-27 초안(§6-1,
--   session_id nullable + report_type 유니크)은 그 결정을 되돌리는 것이라 폐기했다. 새 표는 CREATE 만이라
--   운영 중 잠금·INPLACE 여부를 따질 것이 없다.
--
-- 왜 저장하나: 주간 «통계» 는 저장하지 않는다(weekly-monthly-stat-preaggregation.md — 같은 입력 → 같은 숫자라
--   조회 시 계산). LLM «문장» 은 같은 입력에도 출력이 다르고 호출이 초 단위·한도 소모라 한 번 만들어 읽는다.
--   여기 있는 건 통계가 아니라 «생성된 문장 + 그때 인용한 숫자의 스냅샷» 이다.
--
-- 행의 생애: 조회 시 PENDING 으로 INSERT(+ 아웃박스 GENERATE_WEEKLY_REPORT) → 발행기가 Gemini 호출 →
--   LLM(검증 통과) 또는 TEMPLATE_FALLBACK(검증 실패·한도·거절·재시도 소진). 한 번 종료 상태가 되면 안 바뀐다(§14-2 B-a).
--
-- UNIQUE(member_id, period_start) — 멱등의 원천. 조회 두 번이 동시에 와도 INSERT 는 한 번만 성공하고,
--   진 쪽은 기존 행을 읽는다(goals·notifications 의 UNIQUE 위반 처리 선례).
--
-- cited_metrics JSON — LLM 이 인용했다고 답한 숫자들. 발행기가 입력 집계와 대조해 «없는 숫자» 를 걸러낸 뒤의
--   값이므로, 여기 있는 숫자는 전부 입력에 있던 것이다. 재현·회귀 비교용(§10).
-- generation_model / prompt_version — «누가 이 문장을 썼나». 2.5-flash-lite 가 2026-09-14 에 404 로 사라진 것처럼
--   모델은 설정값이고, 문장은 모델·프롬프트가 바뀌면 달라진다.
CREATE TABLE weekly_reports (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    member_id        BIGINT       NOT NULL,
    period_start     DATE         NOT NULL COMMENT '해당 주 월요일 (WeeklySummaryService 의 start 규약)',
    period_end       DATE         NOT NULL COMMENT '배타적 상한 = period_start + 7일',
    summary_source   VARCHAR(20)  NOT NULL COMMENT 'WeeklyReportSource: PENDING | LLM | TEMPLATE_FALLBACK',
    summary          TEXT         NULL     COMMENT 'LLM 문장. TEMPLATE_FALLBACK 이면 NULL — 템플릿 문장은 조회 시 계산',
    cited_metrics    JSON         NULL     COMMENT '[{"name":..,"value":..}] — 검증을 통과한 인용 숫자',
    generation_model VARCHAR(100) NULL     COMMENT '예: gemini-3.5-flash-lite',
    prompt_version   VARCHAR(50)  NULL     COMMENT 'WeeklyReportPrompt.VERSION',
    fallback_reason  VARCHAR(100) NULL     COMMENT 'TEMPLATE_FALLBACK 일 때 왜 — 폴백 비율의 원인 분해(§10)',
    generated_at     DATETIME     NULL     COMMENT '종료 상태가 된 시각',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_weekly_reports_member_period (member_id, period_start),
    CONSTRAINT fk_weekly_reports_member FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 주간 리포트 차선의 선점 쿼리 — WHERE event_type = 'GENERATE_WEEKLY_REPORT' AND status = 'PENDING' AND next_retry_at ...
-- 기존 idx_outbox_dispatch(status, next_retry_at) 는 그대로 둔다(2026-07-29 실측으로 튜닝된 것, V1 주석). 이 인덱스는
-- 선두가 event_type 이라 그 튜닝과 충돌하지 않고, 기본 차선 쿼리(status 선두)는 여전히 기존 인덱스를 고른다.
-- 추가형(additive) DDL — 행이 적은 표라 짧다.
ALTER TABLE outbox_events
    ADD INDEX idx_outbox_report_dispatch (event_type, status, next_retry_at);
