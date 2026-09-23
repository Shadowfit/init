package com.shadowfit.service.exercise;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 아웃박스 발행기의 공통 골격 — 선점(트랜잭션) → 송신(트랜잭션 밖) → 결과 기록(트랜잭션).
 * 하위 클래스는 «어느 이벤트 타입을 어떻게 보내나»({@link #dispatch})와 폴링 스케줄만 갖는다.
 *
 * <p><b>왜 차선(lane)이 둘인가</b> (report-generation-llm.md §5-1·§5-2 안 A, 2026-08-27 사용자 결정).
 * 기존 발행기 하나가 STOP_ANALYSIS·REATTACH·PUSH·SESSION_COMPLETED 를 한 tick 에 순차로 보냈고,
 * 그 넷은 호출당 ms~수 초라 lease 60초 안에 배치 20건이 끝났다. LLM 호출은 실측 p95 1.7초(flash-lite),
 * 다른 모델은 최대 39초(loadtest/results/gemini-latency-2026-09-14)라 같은 tick 에 섞이면 «한 타입이
 * 느려서 다른 타입의 lease 를 잡아먹는» 일이 트랜잭션 대신 tick 수준에서 재현된다 — #66 이 트랜잭션에서
 * 배운 것과 같은 모양의 사고다. 그래서 타입 집합·배치·lease·재시도 설정을 차선마다 따로 갖는다.
 *
 * <p>선점·상태 전이의 세부(SKIP LOCKED, CAS 펜싱, lease 회수)는 {@link OutboxEventStore} 와
 * {@code OutboxEventRepository} 주석에, 전체 설계는 docs/decisions/outbox-reliable-messaging.md §4-3-1 에 있다.
 */
@Slf4j
public abstract class AbstractOutboxPublisher {

    private final OutboxEventStore store;
    private final SessionMetrics sessionMetrics;
    private final String lane;
    private final int maxRetry;
    private final long maxBackoffSeconds;

    /** 어느 인스턴스가 선점했는지 — 회수된 행을 사후에 추적할 때 쓴다. 차선 이름을 앞에 붙여 로그에서 구분한다. */
    private final String publisherId;

    protected AbstractOutboxPublisher(OutboxEventStore store, SessionMetrics sessionMetrics,
                                      String lane, int maxRetry, long maxBackoffSeconds) {
        this.store = store;
        this.sessionMetrics = sessionMetrics;
        this.lane = lane;
        this.maxRetry = maxRetry;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.publisherId = "pub-" + lane + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 이벤트 하나를 실제로 보낸다. 트랜잭션 <b>밖</b>에서 불린다.
     *
     * <p>실패는 {@link DispatchOutcome} 으로 분류해 돌려주는 게 계약이다. 그래도 던진 {@link RuntimeException} 은
     * {@link DispatchOutcome#RETRY} 로 센다(#759) — retryCount 를 올리고 백오프를 걸며, {@code maxRetry} 를 넘기면
     * FAILED + {@link #onGivenUp} 이다. 예전엔 «상태 못 바꿈 → lease 만료 후 회수» 로 갔는데, 회수는 retryCount 를
     * 안 올려 영구 예외(NPE·잘못된 페이로드)가 lease 주기마다 영원히 재처리됐다.
     * 그러니 일시적 실패를 예외로 올리면 재시도 한도를 소진한다 — 일시적인 건 되도록 RETRY 로 분류해서 돌려줄 것.
     */
    protected abstract DispatchOutcome dispatch(OutboxEvent event, boolean possiblyRedelivered);

    /**
     * 이 이벤트를 영구히 포기할 때(재시도 한도 초과·재시도 무의미) 한 번 불린다 — 행 상태를 FAILED 로 바꾸기 <b>전</b>이다.
     * 순서가 이래야 하는 이유: FAILED 를 먼저 커밋한 뒤 여기서 죽으면 아웃박스는 끝났는데 애그리거트는 영원히 «대기 중»
     * 이다. 반대로 여기까지 하고 FAILED 기록에 실패하면(크래시·lease 상실) 행은 다시 회수돼 재처리되는데, 그때는
     * 애그리거트가 이미 종료 상태라 SENT 로 흡수된다 — 멱등이 이 방향으로만 성립한다.
     * 기본은 아무것도 안 한다. 주간 리포트 차선은 PENDING 행을 TEMPLATE_FALLBACK 으로 옮긴다.
     */
    protected void onGivenUp(OutboxEvent event) {
    }

    protected String publisherId() {
        return publisherId;
    }

    /**
     * 종료 훅 — 이 인스턴스가 들고 있던 lease 를 반납한다 (#208 조치 후보 2, 2026-08-27 채택).
     *
     * <p>graceful shutdown(server.shutdown: graceful)이 켜져 있어도 그건 <b>웹 요청</b>만
     * 보호한다 — {@code @Scheduled} 작업은 별도 설정
     * ({@code spring.task.scheduling.shutdown.await-termination})이 없으면 여기 안 걸린다.
     * 즉 tick 중간(배치 안 다른 행을 아직 못 보낸 상태)에 컨텍스트 종료를 맞을 수 있고, 그 행들은
     * 이 훅이 없으면 lease 만료까지 아무도 못 건드린다 — #208 이 실측으로 확인한 지연이 정확히 이 자리다.
     *
     * <p>SIGKILL(예: {@code docker kill})처럼 훅 자체가 안 도는 죽음에는 이 메서드가
     * 안 불린다 — 그때는 기존 lease 만료 경로가 그대로 안전망이다.
     */
    @PreDestroy
    void releaseLeaseOnShutdown() {
        int released = store.releaseOwnedLeases(publisherId);
        if (released > 0) {
            log.info("종료 — 보유 lease {}건 반납(PENDING 복귀), publisherId={}", released, publisherId);
        }
    }

    /** 폴링 tick 본체. 하위 클래스의 {@code @Scheduled} 메서드가 이걸 부른다(스케줄 문자열이 차선마다 달라 애노테이션은 거기에). */
    protected void tick() {
        // 스케줄러는 물려받을 요청이 없어 tick 1회를 하나의 흐름으로 보고 cid 를 스스로 발급한다
        // (SessionTimeoutScheduler 가 확립한 패턴). 행별 cid 는 아래에서 원 요청 것으로 덮어쓴다.
        try (CorrelationIds.Scope tick = CorrelationIds.startTask("outbox-dispatch-" + lane)) {
            try {
                // 회수분을 먼저 — 이미 한 번 실패(또는 크래시)한 건이라 더 오래 기다린 쪽이다.
                //
                // 두 번째 인자는 «이 행은 이미 한 번 나갔을 수 있다» 는 뜻이다(이슈 #152). 회수분은
                // 이전 발행기가 송신 «도중» 죽었을 수 있어 중복 배달이 될 수 있는데, 그 사실이
                // 지금까지 수신 결과를 해석하는 쪽에 전달되지 않았다.
                dispatchBatch(store.claimStale(publisherId), true);
                dispatchBatch(store.claimPending(publisherId), false);
            } catch (Exception e) {
                // tick 하나가 죽어도 다음 tick 은 돌아야 한다. 여기서 안 잡으면 스케줄러가
                // 해당 작업을 영구 중단시킨다.
                log.error("아웃박스 발행 tick 실패 (lane={})", lane, e);
            }
        }
    }

    private void dispatchBatch(List<OutboxEvent> claimed, boolean possiblyRedelivered) {
        for (OutboxEvent event : claimed) {
            // 행에 적힌 cid 로 복원 — MDC 는 스레드에 매달려 죽지만 DB 에 적힌 cid 는 재시작을 견딘다.
            // sessionId MDC 는 애그리거트가 세션일 때만 — 알림 id 를 세션 자리에 찍으면 로그가 거짓말한다.
            Long sessionId = OutboxEvent.AGGREGATE_TYPE_SESSION.equals(event.getAggregateType())
                    ? event.getAggregateId() : null;
            try (CorrelationIds.Scope perRow = CorrelationIds.withCorrelationId(event.getCorrelationId());
                 CorrelationIds.Scope session = CorrelationIds.withSession(sessionId)) {
                dispatchOne(event, possiblyRedelivered);
            } catch (Exception e) {
                // 한 건의 실패가 배치 전체를 멈추면 안 된다. 여기 오는 건 dispatch() 의 예외가 아니라
                // (그건 dispatchOne 이 RETRY 로 센다, #759) 결과 기록·onGivenUp 단계의 예외다 — DB 에 못 쓰는
                // 상황이라 retryCount 도 못 올린다. 상태를 못 바꾸고 빠져도 행은 PROCESSING 으로 남아
                // lock 만료 후 회수되므로 유실되지 않는다.
                log.error("아웃박스 결과 기록 실패 — lease 만료 후 회수 대기 (id: {})", event.getId(), e);
            }
        }
    }

    /**
     * 송신은 트랜잭션 <b>밖</b>에서, 결과 기록만 짧은 트랜잭션으로.
     *
     * <p>예외의 출처를 둘로 가른다(#759). {@link #dispatch} 가 던진 것은 여기서 RETRY 로 세고 — 한도 안에서
     * 재시도하다 넘기면 독 메시지로 닫는다(RETRY 결과와 같은 길) — 결과 기록 단계에서 난 것은 그대로 올려
     * {@code dispatchBatch} 가 잡게 둔다(PROCESSING 으로 남아 회수). 예외 뒤 recordRetry 자체가 실패해도
     * 후자로 떨어지므로 행은 잃지 않는다.
     */
    private void dispatchOne(OutboxEvent event, boolean possiblyRedelivered) {
        DispatchOutcome outcome;
        try {
            outcome = dispatch(event, possiblyRedelivered);
        } catch (RuntimeException e) {
            // 스택은 여기서 한 번만 남긴다. 아래 RETRY 분기가 한도 초과면 «독 메시지로 종료» 를 따로 찍는다.
            log.error("아웃박스 송신 중 예외 — RETRY 로 센다 (id: {}, {}: {}, 시도: {})",
                    event.getId(), event.getAggregateType(), event.getAggregateId(), event.getRetryCount() + 1, e);
            outcome = DispatchOutcome.RETRY;
        }

        switch (outcome) {
            case SENT -> {
                LocalDateTime now = LocalDateTime.now();
                if (!owned(store.recordSent(event.getId(), publisherId, now), event)) {
                    return;
                }
                sessionMetrics.outboxDispatch(lane, "sent");
                if (event.getCreatedAt() != null) {
                    sessionMetrics.outboxLag(Duration.between(event.getCreatedAt(), now));
                }
            }
            case TERMINAL_FAILED -> {
                // 재시도가 원리상 무의미한 실패 — 한도와 무관하게 즉시 종료 상태로 보낸다. 애그리거트 먼저(위 onGivenUp 주석).
                onGivenUp(event);
                if (!owned(store.recordFailed(event.getId(), publisherId), event)) {
                    return;
                }
                sessionMetrics.outboxDispatch(lane, "failed");
                log.warn("아웃박스 전달 종료(재시도 무의미) - id: {}, {}: {}",
                        event.getId(), event.getAggregateType(), event.getAggregateId());
            }
            case RETRY -> {
                int attempts = event.getRetryCount() + 1;
                if (attempts > maxRetry) {
                    onGivenUp(event);
                    if (!owned(store.recordFailed(event.getId(), publisherId), event)) {
                        return;
                    }
                    sessionMetrics.outboxDispatch(lane, "failed");
                    log.error("아웃박스 재시도 한도 초과 — 독 메시지로 종료 (id: {}, {}: {}, 시도: {})",
                            event.getId(), event.getAggregateType(), event.getAggregateId(), attempts);
                    return;
                }
                LocalDateTime nextAt = LocalDateTime.now().plusSeconds(backoffSeconds(attempts));
                if (!owned(store.recordRetry(event.getId(), publisherId, nextAt), event)) {
                    return;
                }
                sessionMetrics.outboxDispatch(lane, "retry");
            }
        }
    }

    /**
     * 상태 전이가 실제로 반영됐는지 — 0 행이면 <b>lease 가 만료돼 다른 발행기가 이 행을 회수해 간
     * 것</b>이다. 그 경우 우리 결과를 쓰면 새 소유자의 진행을 덮어쓰므로 조용히 물러난다.
     *
     * <p>지표도 올리지 않는다. 우리가 보낸 건 사실이지만 그 행의 결말은 새 소유자가 정하므로,
     * 여기서 세면 같은 행이 두 번 집계된다.
     */
    private boolean owned(int updated, OutboxEvent event) {
        if (updated > 0) {
            return true;
        }
        sessionMetrics.outboxDispatch(lane, "lease-lost");
        log.warn("선점을 잃은 뒤 결과를 기록하려 함 — 다른 발행기가 회수했다 (id: {}, {}: {}). "
                + "lease({}s)가 송신 데드라인 대비 너무 짧지 않은지 확인 필요",
                event.getId(), event.getAggregateType(), event.getAggregateId(), store.lockTimeoutSeconds());
        return false;
    }

    /** 지수 백오프 1s → 2s → 4s … 상한까지. {@code 1L << n} 이 넘치지 않도록 지수를 먼저 자른다. */
    private long backoffSeconds(int attempts) {
        int exponent = Math.min(attempts - 1, 20);
        return Math.min(1L << exponent, maxBackoffSeconds);
    }
}
