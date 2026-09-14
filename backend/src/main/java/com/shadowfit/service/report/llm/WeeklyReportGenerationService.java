package com.shadowfit.service.report.llm;

import com.shadowfit.global.observability.WeeklyReportMetrics;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.report.WeeklyReport;
import com.shadowfit.service.report.WeeklySummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 아웃박스 {@code GENERATE_WEEKLY_REPORT} 한 건 처리 — 집계 → 프롬프트 → Gemini → 검증 → 저장.
 * (report-generation-llm.md §3·§9·§14). 트랜잭션 <b>밖</b>에서 불린다 — 집계 조회와 결과 기록만 각자 짧은 트랜잭션.
 *
 * <p><b>합격 조건은 §9</b> — 어떤 경로로 끝나든 행은 종료 상태(LLM 또는 TEMPLATE_FALLBACK)가 되고 화면은 «리포트 없음» 을
 * 겪지 않는다. RETRY 는 «다시 보내면 될 수 있다»(429·503·타임아웃·서킷)일 때만이고, 검증 실패는 재호출하지 않는다.
 *
 * <table>
 *   <tr><th>상황</th><th>행</th><th>아웃박스</th></tr>
 *   <tr><td>행 없음(회원 탈퇴 CASCADE)</td><td>—</td><td>TERMINAL_FAILED</td></tr>
 *   <tr><td>이미 종료 상태(재배달)</td><td>그대로</td><td>SENT</td></tr>
 *   <tr><td>그 주에 기록 없음</td><td>FALLBACK(no-record)</td><td>SENT — LLM 을 부를 이유가 없다</td></tr>
 *   <tr><td>LLM 비활성(키 없음)</td><td>FALLBACK(disabled)</td><td>SENT</td></tr>
 *   <tr><td>전송 실패·한도·서킷</td><td>PENDING 유지</td><td>RETRY(백오프). 소진 시 발행기 훅이 FALLBACK(exhausted)</td></tr>
 *   <tr><td>거절(4xx·본문 없음)</td><td>FALLBACK(rejected)</td><td>SENT</td></tr>
 *   <tr><td>검증 실패</td><td>FALLBACK(validation:이유)</td><td>SENT</td></tr>
 *   <tr><td>검증 통과</td><td>LLM</td><td>SENT</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportGenerationService {

    private final WeeklyReportStore store;
    private final WeeklySummaryService weeklySummaryService;
    private final GeminiClient geminiClient;
    private final WeeklyReportMetrics metrics;

    public DispatchOutcome dispatch(Long weeklyReportId) {
        WeeklyReport report = store.findById(weeklyReportId).orElse(null);
        if (report == null) {
            log.warn("주간 리포트 행이 없음(회원 탈퇴?) — weeklyReportId: {}", weeklyReportId);
            return DispatchOutcome.TERMINAL_FAILED;
        }
        if (report.getSummarySource().isTerminal()) {
            return DispatchOutcome.SENT;
        }
        Long memberId = report.getMember().getId();
        WeeklySummaryService.Computation computed = weeklySummaryService.compute(memberId, report.getPeriodStart());
        if (computed.summary().thisWeek().isEmpty()) {
            return fallBack(report, "no-record");
        }
        if (!geminiClient.isEnabled()) {
            return fallBack(report, "disabled");
        }

        WeeklyReportPrompt prompt = WeeklyReportPrompt.from(computed.summary(), computed.repCurve(), computed.worstDistribution());
        GeminiResult result;
        long start = System.nanoTime();
        try {
            result = geminiClient.generate(prompt.systemInstruction(), prompt.userText(), prompt.responseSchema(),
                    WeeklyReportPrompt.TEMPERATURE);
        } catch (GeminiTransportException e) {
            metrics.llmCall("transport-error");
            log.warn("Gemini 호출 실패 — 재시도 대상 (weeklyReportId: {}): {}", weeklyReportId, e.getMessage());
            return DispatchOutcome.RETRY;
        } catch (GeminiRejectedException e) {
            metrics.llmCall("rejected");
            log.error("Gemini 가 요청을 거절 — 템플릿으로 (weeklyReportId: {}): {}", weeklyReportId, e.getMessage());
            return fallBack(report, "rejected");
        } finally {
            metrics.llmLatency(Duration.ofNanos(System.nanoTime() - start));
        }

        WeeklyReportOutputValidator.Verdict verdict = WeeklyReportOutputValidator.validate(result.text(), prompt.allowedNumbers());
        if (!verdict.ok()) {
            metrics.llmCall("validation:" + verdict.reason());
            log.warn("Gemini 출력 검증 실패({}) — 템플릿으로 (weeklyReportId: {}, model: {}, finish: {})",
                    verdict.reason(), weeklyReportId, result.model(), result.finishReason());
            return fallBack(report, "validation:" + verdict.reason());
        }
        metrics.llmCall("ok");
        metrics.llmTokens(result.promptTokens(), result.outputTokens());
        store.completeWithLlm(report.getId(), verdict.summary(), verdict.citedMetricsJson(), result.model(),
                WeeklyReportPrompt.VERSION);
        return DispatchOutcome.SENT;
    }

    /** 발행기가 재시도를 소진했을 때 — 행을 영원히 PENDING 으로 두지 않는다. */
    public void giveUp(Long weeklyReportId) {
        if (store.fallBack(weeklyReportId, "exhausted", geminiClient.model(), WeeklyReportPrompt.VERSION)) {
            metrics.fallback("exhausted");
        }
    }

    private DispatchOutcome fallBack(WeeklyReport report, String reason) {
        if (store.fallBack(report.getId(), reason, geminiClient.model(), WeeklyReportPrompt.VERSION)) {
            metrics.fallback(reason);
        }
        return DispatchOutcome.SENT;
    }
}
