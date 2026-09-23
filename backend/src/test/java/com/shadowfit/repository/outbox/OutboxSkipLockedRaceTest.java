package com.shadowfit.repository.outbox;

import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox 선점 쿼리({@code FOR UPDATE SKIP LOCKED})가 실제 MySQL 에서 «발행기끼리 행을 나눠 갖는다» 를
 * 지키는지 검증한다.
 *
 * <p><b>왜 H2 로는 안 되는가.</b> 기본 테스트(H2)는 이 쿼리의 <b>문법</b>만 통과시킨다. 두 트랜잭션이
 * 동시에 잡을 때 잠긴 행을 건너뛰는지·기다리는지는 스토리지 엔진의 행 락 동작이라 InnoDB 에서만
 * 의미가 있다. 이 동작은 PR #63 때 손으로만 확인했고 테스트가 없었다.
 *
 * <p><b>무엇을 고정하나.</b>
 * <ol>
 *   <li>발행기 A 가 행을 잠근 채 트랜잭션을 열어 두는 동안 발행기 B 가 같은 쿼리를 돌리면,
 *       B 는 <b>기다리지 않고</b>(락 대기 시간 안에 끝남) A 가 잡지 않은 행만 받는다.</li>
 *   <li>유실 회수 쿼리는 lease 가 <b>만료된</b> PROCESSING 만 집는다 — 아직 살아 있는 lease 는
 *       남의 송신 중이므로 건드리면 중복 송신이다.</li>
 * </ol>
 *
 * <p><b>실제 발행기와 격리 — 시각을 미래로 민다.</b> 이 클래스의 컨텍스트는 스케줄러를 끄지만
 * ({@code scheduling.enabled=false}, {@code SessionCompletedFeedFanoutRaceTest} 와 같은 설정이라 컨텍스트를
 * 같이 쓴다), <b>캐시된 다른 컨텍스트</b>(스케줄러가 켜진 race 테스트)의 {@code OutboxPublisher} 는 같은
 * 컨테이너를 1초마다 폴링한다. 전체 스위트에서 실제로 그 발행기가 이 테스트의 행을 먼저 집어 가
 * B 가 1건만 받은 적이 있다(2026-09-24). 그래서 행의 {@code next_retry_at}·{@code lock_expires_at} 을
 * {@link #FUTURE} 뒤로 두고, 선점 쿼리의 {@code :now} 에 그 미래 시각을 넘긴다 — 실제 발행기는
 * {@code LocalDateTime.now()} 로 물으므로 이 행들을 절대 못 집는다.
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@DisplayName("outbox SKIP LOCKED — 발행기끼리 행을 나눠 갖는다 (실 MySQL)")
class OutboxSkipLockedRaceTest extends MySqlContainerSupport {

    private static final List<String> TYPES = List.of("STOP_ANALYSIS");
    private static final int ROWS = 10;
    private static final int BATCH = 4;
    /** A 가 락을 쥐고 버티는 최대 시간. B 가 이보다 빨리 끝났다면 A 의 락을 기다리지 않은 것이다. */
    private static final long HOLD_SECONDS = 30;
    /** 실제 발행기의 «지금» 보다 확실히 뒤 — 테스트가 이 시각을 :now 로 넘긴다. */
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(1);

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // 큐 표라 다른 테스트가 남긴 PENDING 이 섞이면 «누가 무엇을 집었나» 를 셀 수 없다. 이 클래스가
        // 도는 동안 스케줄러는 꺼져 있으므로 비워도 경합하는 쪽이 없다.
        jdbcTemplate.update("DELETE FROM outbox_events");
        for (int i = 0; i < ROWS; i++) {
            jdbcTemplate.update("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, status, next_retry_at) "
                    + "VALUES ('SESSION', ?, 'STOP_ANALYSIS', JSON_OBJECT('sessionId', ?), 'PENDING', ?)", i, i, FUTURE);
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    @DisplayName("A 가 잠근 동안 B 는 기다리지 않고 A 가 안 잡은 행만 받는다")
    void concurrentClaims_areDisjointAndDoNotBlock() throws Exception {
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CompletableFuture<Set<Long>> a = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            Set<Long> ids = idsOf(outboxEventRepository.lockPendingBatch(TYPES, FUTURE.plusMinutes(1), BATCH));
            aLocked.countDown();
            await(bDone);   // B 가 끝날 때까지 락을 쥔 채 트랜잭션을 열어 둔다
            return ids;
        }));

        assertThat(aLocked.await(HOLD_SECONDS, TimeUnit.SECONDS)).as("A 가 선점을 마쳐야 한다").isTrue();

        long started = System.nanoTime();
        Set<Long> b = tx.execute(status ->
                idsOf(outboxEventRepository.lockPendingBatch(TYPES, FUTURE.plusMinutes(1), BATCH)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        bDone.countDown();
        Set<Long> aIds = a.get(HOLD_SECONDS * 2, TimeUnit.SECONDS);

        assertThat(aIds).hasSize(BATCH);
        assertThat(b).as("B 도 한 배치를 채운다 — 잠긴 행을 건너뛰고 다음 행을 집는다").hasSize(BATCH);
        assertThat(b).as("SKIP LOCKED — 같은 행을 두 발행기가 집으면 중복 송신이다").doesNotContainAnyElementsOf(aIds);
        // 문턱은 성능 목표가 아니라 구조에서 나온다: A 는 B 가 끝나야(bDone) 락을 놓고, 못 받으면
        // HOLD_SECONDS 뒤에야 놓는다. B 가 락을 기다렸다면 HOLD_SECONDS 이전엔 돌아올 수 없다.
        assertThat(elapsedMs).as("B 가 A 의 락에 막히지 않았다 (%d ms)", elapsedMs)
                .isLessThan(TimeUnit.SECONDS.toMillis(HOLD_SECONDS));
    }

    @Test
    @DisplayName("회수 쿼리는 lease 가 만료된 PROCESSING 만 집는다")
    void staleReclaim_picksOnlyExpiredLeases() {
        LocalDateTime now = FUTURE.plusMinutes(1);
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM outbox_events ORDER BY id", Long.class);
        long expired = ids.get(0);
        long alive = ids.get(1);
        jdbcTemplate.update("UPDATE outbox_events SET status = 'PROCESSING', locked_by = 'dead', lock_expires_at = ? WHERE id = ?",
                now.minusMinutes(1), expired);
        jdbcTemplate.update("UPDATE outbox_events SET status = 'PROCESSING', locked_by = 'busy', lock_expires_at = ? WHERE id = ?",
                now.plusMinutes(1), alive);

        Set<Long> reclaimed = new TransactionTemplate(transactionManager).execute(status ->
                idsOf(outboxEventRepository.lockStaleProcessingBatch(TYPES, now, ROWS)));

        assertThat(reclaimed).containsExactly(expired);
    }

    private static Set<Long> idsOf(List<OutboxEvent> events) {
        Set<Long> ids = new HashSet<>();
        events.forEach(e -> ids.add(e.getId()));
        return ids;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(HOLD_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("B 가 " + HOLD_SECONDS + "초 안에 끝나지 않았다 — A 의 락에 막혔을 수 있다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
