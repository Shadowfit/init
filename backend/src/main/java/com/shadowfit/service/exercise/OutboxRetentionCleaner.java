package com.shadowfit.service.exercise;

import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code outbox_events} 보존 정리 — 지난 SENT·FAILED 행을 지운다 (#793, docs/decisions/outbox-retention-cleanup.md).
 *
 * <p>정리를 부르는 코드가 없어서 이 표는 영구히 늘기만 했다(정리 쿼리는 있었으나 호출처 0).
 *
 * <p><b>결정(2026-11-19):</b> SENT 7일 · FAILED 90일 · A1(주기 DELETE) · 인덱스 추가 없음 · 다중 인스턴스는 감수(E1).
 * <b>PENDING·PROCESSING 은 나이와 무관하게 절대 지우지 않는다</b> — 아직 전달 책임이 남은 행이다.
 *
 * <h3>왜 «PK 순회 배치» 인가 (C3)</h3>
 * 기존 메서드({@code DELETE WHERE status=? AND created_at<?})를 그대로 켜면 ① {@code LIMIT} 이 없어 처음 켜는 순간
 * 쌓인 행 전체를 문장 하나로 지우고 ② {@code status} 인덱스의 SENT 구간에 락이 걸려 발행기의 {@code markSent}
 * (같은 구간에 쓴다)가 기다릴 수 있다. 여기서는 오래된 쪽부터 <b>PK 로 n 행씩</b> 걸으며 그 PK 범위만 지운다 —
 * 락이 «새로 쓰이는 곳» 과 떨어진다. 짧은 트랜잭션 하나가 배치 하나다.
 *
 * <p>⚠️ <b>«겹치지 않는다» 는 측정으로 확인한 사실이 아니라 가설이다</b>(문서 §3-1·§6 M1). 그리고 배치 크기의 기본값은
 * <b>잠정값</b>이다(§3-3, M2 측정으로 유도할 것) — 이 구현이 측정을 대신하지 않는다.
 *
 * <h3>순서 가정</h3>
 * id(AUTO_INCREMENT)가 {@code created_at} 순서와 같다고 본다. 어긋나도(서버 시계가 뒤로 감 등) 틀린 행을 지우지는 않는다 —
 * DELETE 가 <b>행마다</b> status·created_at 조건을 다시 건다. 정리가 조금 늦어질 뿐이다.
 */
@Slf4j
@Service
public class OutboxRetentionCleaner {

    private final OutboxEventRepository repository;
    private final SessionMetrics sessionMetrics;
    private final TransactionTemplate tx;

    private final boolean enabled;
    private final long sentDays;
    private final long failedDays;
    private final int batchSize;

    public OutboxRetentionCleaner(OutboxEventRepository repository,
                                  SessionMetrics sessionMetrics,
                                  PlatformTransactionManager txManager,
                                  @Value("${outbox.retention.enabled:true}") boolean enabled,
                                  /* 결정 2026-11-19. SENT 를 다시 읽는 코드가 없고 쓸모는 correlation_id 로 앱 로그와 잇는 것뿐이다. */
                                  @Value("${outbox.retention.sent-days:7}") long sentDays,
                                  /* 결정 2026-11-19. 사람이 봐야 할 사건인데 확인 화면·절차가 아직 없어 길게 둔다. */
                                  @Value("${outbox.retention.failed-days:90}") long failedDays,
                                  /* 잠정값 — 문서 §3-3: «배치 하나의 락 보유 시간이 발행기 tick(1초)보다 충분히 짧아야 한다» 가 상한 조건이고
                                   * 값은 M2 측정으로 유도한다. 그 전까지 보수적으로 작게 둔다. */
                                  @Value("${outbox.retention.batch-size:200}") int batchSize) {
        if (sentDays <= 0 || failedDays <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("outbox.retention.* 는 양수여야 한다: sent-days=" + sentDays
                    + ", failed-days=" + failedDays + ", batch-size=" + batchSize);
        }
        this.repository = repository;
        this.sessionMetrics = sessionMetrics;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
        this.sentDays = sentDays;
        this.failedDays = failedDays;
        this.batchSize = batchSize;
    }

    /** FAILED 누적 게이지 — «지금 몇 건 남아 있나». 카운터는 발생 수라 이 값을 모른다. */
    @PostConstruct
    void registerGauge() {
        sessionMetrics.registerOutboxFailedGauge(() -> repository.countByStatus(OutboxStatus.FAILED));
    }

    @Scheduled(fixedDelayString = "${outbox.retention.interval-minutes:60}m",
               initialDelayString = "${outbox.retention.initial-delay-minutes:5}m")
    public void scheduledCleanup() {
        if (!enabled) {
            return;
        }
        try {
            Result r = cleanup(LocalDateTime.now());
            if (r.sentDeleted() + r.failedDeleted() > 0) {
                log.info("outbox 보존 정리 — SENT {}건, FAILED {}건 삭제 (배치 {}회)",
                        r.sentDeleted(), r.failedDeleted(), r.batches());
            }
        } catch (Exception e) {
            // 정리 실패가 발행기에 영향을 주면 안 된다 — 다음 주기에 다시 한다. 삭제는 멱등이다.
            log.error("outbox 보존 정리 실패", e);
        }
    }

    /** 정리 1회 결과. */
    public record Result(long sentDeleted, long failedDeleted, int batches) {}

    /**
     * 기준 시각 {@code now} 로 정리를 돈다. 테스트가 시각을 주입할 수 있게 공개한다.
     *
     * <p>삭제 건수 지표는 상태별로 센다 — 한 번에 몇 건이 지워지는지가 보여야 배치 반복이 끝나는지 안다.
     */
    public Result cleanup(LocalDateTime now) {
        LocalDateTime sentBefore = now.minusDays(sentDays);
        LocalDateTime failedBefore = now.minusDays(failedDays);
        // 둘 중 «더 최근» 기준 — 이보다 새 행은 어떤 상태든 지울 게 없으니 순회를 멈춘다.
        LocalDateTime newest = sentBefore.isAfter(failedBefore) ? sentBefore : failedBefore;

        long cursor = 0L;
        int batches = 0;
        long sentTotal = 0;
        long failedTotal = 0;

        while (true) {
            List<OutboxEventRepository.IdAndCreatedAt> page =
                    repository.findIdAndCreatedAtAfter(cursor, PageRequest.of(0, batchSize));
            if (page.isEmpty()) {
                break;
            }
            // 가장 오래된 행이 이미 기준보다 새 것이면 뒤는 전부 더 새 것이다 — 끝.
            if (!page.get(0).getCreatedAt().isBefore(newest)) {
                break;
            }
            long fromId = page.get(0).getId();
            long toId = page.get(page.size() - 1).getId();

            // 상태별 건수를 따로 세려고 두 번 지운다 — 같은 PK 범위 안이라 비용은 작고, 지표가 상태별로 갈린다.
            int sent = tx.execute(s -> repository.deleteExpiredInRange(
                    fromId, toId, OutboxStatus.SENT, sentBefore));
            int failed = tx.execute(s -> repository.deleteExpiredInRange(
                    fromId, toId, OutboxStatus.FAILED, failedBefore));

            sentTotal += sent;
            failedTotal += failed;
            batches++;
            if (sent > 0) {
                sessionMetrics.outboxCleanupDeleted("SENT", sent);
            }
            if (failed > 0) {
                sessionMetrics.outboxCleanupDeleted("FAILED", failed);
            }

            // 지워진 행이 없어도 커서는 전진한다 — 살아남는 오래된 행(젊은 FAILED, 처리 중인 PENDING)을 계속 밟지 않게.
            cursor = toId;
            if (page.size() < batchSize) {
                break;
            }
        }
        return new Result(sentTotal, failedTotal, batches);
    }
}
