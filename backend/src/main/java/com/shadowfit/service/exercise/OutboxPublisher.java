package com.shadowfit.service.exercise;

import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.group.SessionCompletedFeedService;
import com.shadowfit.service.notification.push.PushDispatchService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 기본 차선 발행기 — {@code PENDING} 행을 집어 실제로 송신하고 결과를 행 상태로 되돌린다.
 * 상대는 타입에 따라 AI(gRPC)·Expo Push(HTTP)·같은 DB 의 그룹 애그리거트다 — 두 번째 용처는
 * {@link PushDispatchService}, 세 번째는 {@link SessionCompletedFeedService}.
 * 주간 리포트 LLM 호출({@link OutboxEventType#GENERATE_WEEKLY_REPORT})은 이 차선이 아니라
 * {@code WeeklyReportOutboxPublisher} 가 집는다 — 이유는 {@link AbstractOutboxPublisher} 클래스 주석.
 *
 * <p>[전체 그림] {@code endSession} 은 세션 변경과 통보 행 INSERT 를 한 트랜잭션에 커밋하고 끝난다
 * (gRPC 없음). 전달 책임은 여기가 진다 — 실패하면 행이 남아 다음 tick 에 다시 시도되므로,
 * 인스턴스가 죽어도 통보가 증발하지 않는다(at-least-once).
 *
 * <p>[송신을 트랜잭션 밖에서 하는 이유] gRPC 는 최대 {@code GRPC_CALL_TIMEOUT_SECONDS} 만큼 걸린다.
 * 그 시간 동안 DB 트랜잭션을 열어두면 커넥션을 외부 I/O 시간만큼 점유한다. 그래서
 * <b>선점(트랜잭션) → 송신(트랜잭션 밖) → 결과 기록(트랜잭션)</b> 세 단계로 나눈다.
 *
 * <p>[그래서 소유권이 필요하다] 트랜잭션이 끝나면 행 락도 풀리므로, 송신 중인 행을 다른 발행기가
 * 집지 않게 하려면 락이 아니라 <b>상태</b>로 표시해야 한다 — 선점 시 {@link OutboxStatus#PROCESSING}
 * 으로 바꾸고 만료 시각을 박는다. 송신 도중 죽으면 만료 후 회수된다.
 * (docs/decisions/outbox-reliable-messaging.md §4-3-1)
 */
@Slf4j
@Service
public class OutboxPublisher extends AbstractOutboxPublisher {

    public static final String LANE = "default";

    /** 이 차선이 집는 타입. 주간 리포트만 빼고 전부 — 새 타입을 enum 에 더하면 어느 차선인지 여기서 정한다. */
    public static final Set<OutboxEventType> TYPES = Set.of(
            OutboxEventType.STOP_ANALYSIS, OutboxEventType.REATTACH_ANALYSIS,
            OutboxEventType.PUSH_NOTIFICATION, OutboxEventType.SESSION_COMPLETED);

    private final OutboxEventRepository outboxRepository;
    private final ExerciseAnalysisService analysisService;
    private final PushDispatchService pushDispatchService;
    private final SessionCompletedFeedService sessionCompletedFeedService;
    private final SessionMetrics sessionMetrics;

    public OutboxPublisher(@Qualifier("outboxEventStore") OutboxEventStore outboxEventStore,
                           OutboxEventRepository outboxRepository,
                           ExerciseAnalysisService analysisService,
                           PushDispatchService pushDispatchService,
                           SessionCompletedFeedService sessionCompletedFeedService,
                           SessionMetrics sessionMetrics,
                           /* 이 횟수를 넘기면 독 메시지로 보고 종료 상태로 보낸다. */
                           @Value("${outbox.publisher.max-retry:10}") int maxRetry,
                           /* 백오프 상한. 서킷브레이커가 빠른 실패를 담당하므로 발행기는 느긋해도 된다. */
                           @Value("${outbox.publisher.max-backoff-seconds:300}") long maxBackoffSeconds) {
        super(outboxEventStore, sessionMetrics, LANE, maxRetry, maxBackoffSeconds);
        this.outboxRepository = outboxRepository;
        this.analysisService = analysisService;
        this.pushDispatchService = pushDispatchService;
        this.sessionCompletedFeedService = sessionCompletedFeedService;
        this.sessionMetrics = sessionMetrics;
    }

    @PostConstruct
    void registerGauge() {
        // 차선 무관 전체 PENDING — «조용히 쌓이는 것» 을 보는 게 목적이라 합계가 맞다.
        sessionMetrics.registerOutboxPendingGauge(() -> outboxRepository.countByStatus(OutboxStatus.PENDING));
    }

    /**
     * 폴링 tick. 간격이 곧 통보 지연의 하한이라 짧게 잡는다 — 사용자가 이 통보를 기다리며
     * 블록되지는 않지만, 결과 회수가 늦어질 이유도 없다.
     */
    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}",
               initialDelayString = "${outbox.publisher.initial-delay-ms:10000}")
    public void dispatchPending() {
        tick();
    }

    @Override
    protected DispatchOutcome dispatch(OutboxEvent event, boolean possiblyRedelivered) {
        return switch (event.getEventType()) {
            case STOP_ANALYSIS -> analysisService.stopAnalysis(event.getAggregateId(), possiblyRedelivered);
            // possiblyRedelivered 를 안 쓴다 — 재부착은 이미 AI 쪽 already_active 로 멱등해서
            // (§2-1 stopAnalysis 와 달리) 회수분 구분이 결과 해석에 영향을 주지 않는다.
            case REATTACH_ANALYSIS -> analysisService.reattachFromOutbox(event.getAggregateId());
            // possiblyRedelivered 를 안 쓴다 — 이미 폰에 갔는지 알 길이 없어 구분해도 할 수 있는 게 없다
            // (social-cheer-and-group-feed.md §4-3 ⑨). aggregateId 는 notification id.
            case PUSH_NOTIFICATION -> pushDispatchService.dispatch(event.getAggregateId());
            // possiblyRedelivered 를 안 쓴다 — 회수분이든 아니든 group_events.source_id 의 exists·UNIQUE 가
            // 같은 글의 재생성을 막는다(social-cheer-and-group-feed.md §4-4 ④ c). aggregateId 는 session id.
            case SESSION_COMPLETED -> sessionCompletedFeedService.dispatch(event.getAggregateId());
            // 선점 쿼리가 TYPES 로 거르므로 여기 올 수 없다 — 왔다면 차선 설정이 어긋난 것이라 조용히 넘기지 않는다.
            case GENERATE_WEEKLY_REPORT -> throw new IllegalStateException(
                    "기본 차선이 주간 리포트 이벤트를 집었다 — OutboxEventStore 의 타입 집합 확인 (id=" + event.getId() + ")");
        };
    }
}
