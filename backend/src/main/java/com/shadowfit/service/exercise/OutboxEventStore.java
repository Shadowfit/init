package com.shadowfit.service.exercise;

import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 아웃박스 행의 선점·결과 기록 트랜잭션 경계. 발행기({@link AbstractOutboxPublisher})가 이 빈을 통해서만
 * 상태를 바꾼다 — 별도 빈이라 {@code @Transactional}이 Spring 프록시를 정상적으로 타고, self 주입이
 * 필요 없다 (이슈 #175: 자기호출은 AOP 프록시를 우회해 {@code @Transactional}이 조용히 무시된다).
 *
 * <p><b>차선(lane)마다 인스턴스가 하나다</b> — {@link OutboxLaneConfig} 가 «어느 타입을 몇 건씩 얼마 동안
 * 선점하나» 를 다르게 넣어 두 개를 만든다. 선점 쿼리가 {@code event_type IN (:types)} 로 걸러서
 * 두 차선이 서로의 행을 집지 않는다.
 */
@Slf4j
public class OutboxEventStore {

    private final OutboxEventRepository outboxRepository;
    private final Set<String> typeNames;
    private final int batchSize;
    private final long lockTimeoutSeconds;

    /**
     * @param lockTimeoutSeconds 선점 유효 시간. 송신 데드라인 × 배치보다 넉넉해야 한다 — 아직 송신 중인 행을
     *                           다른 발행기가 "죽은 것"으로 보고 회수하면 불필요한 중복이 난다.
     */
    public OutboxEventStore(OutboxEventRepository outboxRepository, Set<OutboxEventType> types,
                            int batchSize, long lockTimeoutSeconds) {
        this.outboxRepository = outboxRepository;
        this.typeNames = types.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.batchSize = batchSize;
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    public long lockTimeoutSeconds() {
        return lockTimeoutSeconds;
    }

    /**
     * 신규·재시도분 선점. 조회와 상태 전이가 <b>한 트랜잭션</b>이어야 한다 — SKIP LOCKED 로 잡은
     * 락은 트랜잭션과 함께 풀리므로, 상태를 안 바꾸고 커밋하면 다른 발행기가 같은 행을 집는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPending(String publisherId) {
        return claim(outboxRepository.lockPendingBatch(typeNames, LocalDateTime.now(), batchSize), publisherId);
    }

    /** 선점 후 만료된 {@code PROCESSING} 회수 — 송신 도중 죽은 발행기가 남긴 행이다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimStale(String publisherId) {
        List<OutboxEvent> stale = outboxRepository.lockStaleProcessingBatch(typeNames, LocalDateTime.now(), batchSize);
        if (!stale.isEmpty()) {
            log.warn("만료된 선점 행 회수 - {}건 (이전 발행기가 송신 중 종료됐을 수 있음)", stale.size());
        }
        return claim(stale, publisherId);
    }

    private List<OutboxEvent> claim(List<OutboxEvent> rows, String publisherId) {
        if (rows.isEmpty()) {
            return rows;
        }
        outboxRepository.markProcessing(
                rows.stream().map(OutboxEvent::getId).toList(),
                publisherId,
                LocalDateTime.now().plusSeconds(lockTimeoutSeconds));
        return rows;
    }

    /** @return 갱신된 행 수. 0 이면 소유권을 잃은 것(호출부 {@code owned} 참고). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordSent(Long id, String lockedBy, LocalDateTime sentAt) {
        return outboxRepository.markSent(id, lockedBy, sentAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordRetry(Long id, String lockedBy, LocalDateTime nextRetryAt) {
        return outboxRepository.markForRetry(id, lockedBy, nextRetryAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailed(Long id, String lockedBy) {
        return outboxRepository.markFailed(id, lockedBy);
    }

    /** @return 반납된 행 수. {@link AbstractOutboxPublisher#releaseLeaseOnShutdown} 전용. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseOwnedLeases(String lockedBy) {
        return outboxRepository.releaseOwnedLeases(lockedBy);
    }
}
