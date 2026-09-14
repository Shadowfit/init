package com.shadowfit.service.exercise;

import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Set;

/**
 * 아웃박스 차선별 {@link OutboxEventStore} — 타입 집합·배치 크기·lease 가 차선마다 다르다
 * (report-generation-llm.md §5-2 안 A, {@link AbstractOutboxPublisher} 클래스 주석).
 *
 * <p>기본 차선이 {@code @Primary} 다 — 기존 주입 지점(테스트 포함)이 타입만으로 받던 것을 그대로 두기 위해서다.
 * 주간 리포트 차선은 이름({@code weeklyReportOutboxStore})으로만 받는다.
 */
@Configuration
public class OutboxLaneConfig {

    @Bean
    @Primary
    OutboxEventStore outboxEventStore(OutboxEventRepository repository,
                                      @Value("${outbox.publisher.batch-size:20}") int batchSize,
                                      /* gRPC 데드라인(5초)×배치보다 넉넉해야 한다 — 기존 값 그대로 */
                                      @Value("${outbox.publisher.lock-timeout-seconds:60}") long lockTimeoutSeconds) {
        return new OutboxEventStore(repository, OutboxPublisher.TYPES, batchSize, lockTimeoutSeconds);
    }

    /**
     * 주간 리포트(LLM) 차선. 숫자의 근거는 application.yml {@code outbox.weekly-report.*} 주석 —
     * loadtest/results/gemini-latency-2026-09-14 실측에서 유도했다.
     */
    @Bean
    OutboxEventStore weeklyReportOutboxStore(OutboxEventRepository repository,
                                             @Value("${outbox.weekly-report.batch-size:5}") int batchSize,
                                             @Value("${outbox.weekly-report.lock-timeout-seconds:60}") long lockTimeoutSeconds) {
        return new OutboxEventStore(repository, Set.of(OutboxEventType.GENERATE_WEEKLY_REPORT), batchSize, lockTimeoutSeconds);
    }
}
