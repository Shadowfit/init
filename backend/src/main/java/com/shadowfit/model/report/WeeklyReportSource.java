package com.shadowfit.model.report;

/**
 * {@code weekly_reports.summary_source} — 이 행의 문장을 «누가» 썼나. PENDING 은 아직 아무도.
 * DB ENUM 이 아니라 VARCHAR + Java enum 인 이유는 V16 이 지운 {@code report_type} 과 같은 함정 회피
 * (notifications.type 관례).
 */
public enum WeeklyReportSource {
    /** 조회가 행을 만들었고 발행기가 아직 안 집었다. 화면은 템플릿 문장 + «준비 중». */
    PENDING,
    /** Gemini 출력이 검증(JSON·인용 숫자·한국어)을 통과해 저장됐다. */
    LLM,
    /** 검증 실패·거절·재시도 소진·기록 없는 주 — 문장은 없고 조회 시 템플릿으로 채운다. 원인은 {@code fallback_reason}. */
    TEMPLATE_FALLBACK;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
