package com.shadowfit.service.report.llm;

import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.service.exercise.AbstractOutboxPublisher;
import com.shadowfit.service.exercise.OutboxEventStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 주간 리포트(LLM) 차선 발행기 — {@code GENERATE_WEEKLY_REPORT} 만 집는다 (report-generation-llm.md §5-2 안 A).
 * 왜 기본 차선과 갈랐는지는 {@link AbstractOutboxPublisher} 클래스 주석, 설정 숫자의 근거는 application.yml
 * {@code outbox.weekly-report.*} 주석.
 *
 * <p>폴링 간격이 기본 차선(1초)보다 긴 이유: 이 이벤트는 사용자가 기다리며 블록되는 것이 아니고(첫 조회는 이미
 * 템플릿으로 응답했다), tick 마다 도는 선점 쿼리를 초당 두 벌로 만들 이유가 없다.
 */
@Service
public class WeeklyReportOutboxPublisher extends AbstractOutboxPublisher {

    public static final String LANE = "weekly-report";

    private final WeeklyReportGenerationService generationService;

    public WeeklyReportOutboxPublisher(@Qualifier("weeklyReportOutboxStore") OutboxEventStore store,
                                       SessionMetrics sessionMetrics,
                                       WeeklyReportGenerationService generationService,
                                       @Value("${outbox.weekly-report.max-retry:12}") int maxRetry,
                                       @Value("${outbox.weekly-report.max-backoff-seconds:3600}") long maxBackoffSeconds) {
        super(store, sessionMetrics, LANE, maxRetry, maxBackoffSeconds);
        this.generationService = generationService;
    }

    @Scheduled(fixedDelayString = "${outbox.weekly-report.poll-interval-ms:5000}",
               initialDelayString = "${outbox.weekly-report.initial-delay-ms:15000}")
    public void dispatchPending() {
        tick();
    }

    @Override
    protected DispatchOutcome dispatch(OutboxEvent event, boolean possiblyRedelivered) {
        // possiblyRedelivered 를 안 쓴다 — 행이 이미 종료 상태면 SENT 로 흡수된다(WeeklyReport.completeWithLlm 의 멱등).
        // 중복 «호출» 은 날 수 있다(회수분) — 그 비용은 at-least-once 의 대가로 감수한다.
        return generationService.dispatch(event.getAggregateId());
    }

    @Override
    protected void onGivenUp(OutboxEvent event) {
        generationService.giveUp(event.getAggregateId());
    }
}
