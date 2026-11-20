package com.shadowfit.integration;

import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.exercise.OutboxRetentionCleaner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * outbox 보존 정리 (#793) — SENT 7일 · FAILED 90일, PK 범위 배치(C3).
 *
 * <p>가장 중요한 것은 <b>지우면 안 되는 것을 안 지운다</b>는 점이다 — PENDING·PROCESSING 은 나이와 무관하게 남아야 한다
 * (아직 전달 책임이 있다). 그다음이 배치 순회가 끝난다는 것과, 한 번 더 돌려도 결과가 같다는 것(다중 인스턴스 감수 E1 의 전제).
 *
 * <p>⚠️ 이 테스트는 H2 라서 «발행기의 markSent 와 락이 겹치지 않는다» 는 가설(문서 §3-1)을 <b>검증하지 않는다</b> — 그건 실 MySQL
 * 측정(M1)의 몫이다.
 */
@SpringBootTest
@Transactional
@DisplayName("outbox 보존 정리")
class OutboxRetentionCleanerIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    @Autowired private OutboxEventRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private SimpleMeterRegistry registry;
    private SessionMetrics metrics;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        registry = new SimpleMeterRegistry();
        metrics = new SessionMetrics(registry);
    }

    private OutboxRetentionCleaner cleaner(int batchSize) {
        return new OutboxRetentionCleaner(repository, metrics, txManager, true, 7, 90, batchSize);
    }

    /** 상태와 나이(일)를 가진 행 하나. id 는 만든 순서대로 오른다 — 오래된 행을 먼저 만든다. */
    private Long row(OutboxStatus status, long ageDays) {
        OutboxEvent e = repository.saveAndFlush(OutboxEvent.stopAnalysis(1L, "cid"));
        jdbc.update("update outbox_events set status = ?, created_at = ? where id = ?",
                status.name(), NOW.minusDays(ageDays), e.getId());
        return e.getId();
    }

    private boolean exists(Long id) {
        Integer n = jdbc.queryForObject("select count(*) from outbox_events where id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    private double deleted(String status) {
        return registry.counter("shadowfit.outbox.cleanup.deleted", "status", status).count();
    }

    @Test
    @DisplayName("7일 지난 SENT 와 90일 지난 FAILED 만 지운다 — 경계 안쪽은 남는다")
    void deletesOnlyExpired() {
        Long sentOld = row(OutboxStatus.SENT, 8);
        Long sentYoung = row(OutboxStatus.SENT, 6);
        Long failedOld = row(OutboxStatus.FAILED, 91);
        Long failedYoung = row(OutboxStatus.FAILED, 89);
        Long failedMiddle = row(OutboxStatus.FAILED, 30);   // SENT 였다면 지웠을 나이 — FAILED 는 90일까지 남는다

        OutboxRetentionCleaner.Result r = cleaner(200).cleanup(NOW);

        assertThat(exists(sentOld)).isFalse();
        assertThat(exists(failedOld)).isFalse();
        assertThat(exists(sentYoung)).isTrue();
        assertThat(exists(failedYoung)).isTrue();
        assertThat(exists(failedMiddle)).isTrue();
        assertThat(r.sentDeleted()).isEqualTo(1);
        assertThat(r.failedDeleted()).isEqualTo(1);
        assertThat(deleted("SENT")).isEqualTo(1.0);
        assertThat(deleted("FAILED")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PENDING·PROCESSING 은 얼마나 오래됐든 절대 지우지 않는다 — 아직 전달 책임이 남은 행")
    void neverDeletesPendingOrProcessing() {
        Long pendingAncient = row(OutboxStatus.PENDING, 1000);
        Long processingAncient = row(OutboxStatus.PROCESSING, 1000);
        Long sentAncient = row(OutboxStatus.SENT, 1000);

        cleaner(200).cleanup(NOW);

        assertThat(exists(pendingAncient)).isTrue();
        assertThat(exists(processingAncient)).isTrue();
        assertThat(exists(sentAncient)).isFalse();
    }

    @Test
    @DisplayName("배치보다 많은 행도 끝까지 걷고, 살아남는 오래된 행(PENDING)이 사이에 끼어 있어도 건너뛰어 나머지를 지운다")
    void walksAcrossBatches_pastSurvivors() {
        // 배치 크기 2: [SENT, PENDING] [SENT, SENT] [FAILED(91), SENT(8)] [SENT(1)=젊음]
        Long a = row(OutboxStatus.SENT, 20);
        Long pending = row(OutboxStatus.PENDING, 20);
        Long b = row(OutboxStatus.SENT, 15);
        Long c = row(OutboxStatus.SENT, 12);
        Long d = row(OutboxStatus.FAILED, 91);
        Long e = row(OutboxStatus.SENT, 8);
        Long young = row(OutboxStatus.SENT, 1);

        OutboxRetentionCleaner.Result r = cleaner(2).cleanup(NOW);

        assertThat(List.of(a, b, c, d, e)).noneMatch(this::exists);
        assertThat(exists(pending)).isTrue();
        assertThat(exists(young)).isTrue();
        assertThat(r.sentDeleted()).isEqualTo(4);
        assertThat(r.failedDeleted()).isEqualTo(1);
        assertThat(r.batches()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("한 번 더 돌려도 결과가 같다 — 두 인스턴스가 같이 돌아도 안전하다는 전제(E1)")
    void idempotent() {
        row(OutboxStatus.SENT, 30);
        row(OutboxStatus.SENT, 30);
        row(OutboxStatus.SENT, 1);

        OutboxRetentionCleaner.Result first = cleaner(2).cleanup(NOW);
        OutboxRetentionCleaner.Result second = cleaner(2).cleanup(NOW);

        assertThat(first.sentDeleted()).isEqualTo(2);
        assertThat(second.sentDeleted()).isZero();
        assertThat(second.failedDeleted()).isZero();
    }

    @Test
    @DisplayName("빈 표와 «전부 젊은» 표는 아무것도 지우지 않고 곧바로 끝난다")
    void emptyAndAllYoung() {
        assertThat(cleaner(200).cleanup(NOW).batches()).isZero();

        Long y1 = row(OutboxStatus.SENT, 1);
        Long y2 = row(OutboxStatus.FAILED, 5);

        OutboxRetentionCleaner.Result r = cleaner(200).cleanup(NOW);

        assertThat(r.sentDeleted() + r.failedDeleted()).isZero();
        assertThat(exists(y1)).isTrue();
        assertThat(exists(y2)).isTrue();
    }

    @Test
    @DisplayName("배치 크기·보존 일수가 양수가 아니면 기동 시점에 거부한다")
    void rejectsNonPositiveConfig() {
        assertThatThrownBy(() -> new OutboxRetentionCleaner(repository, metrics, txManager, true, 7, 90, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetentionCleaner(repository, metrics, txManager, true, 0, 90, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetentionCleaner(repository, metrics, txManager, true, 7, -1, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FAILED 누적 게이지는 지금 남은 FAILED 수를 보여 준다")
    void failedGauge_reportsRemaining() {
        row(OutboxStatus.FAILED, 5);
        row(OutboxStatus.FAILED, 6);
        row(OutboxStatus.SENT, 5);

        metrics.registerOutboxFailedGauge(() -> repository.countByStatus(OutboxStatus.FAILED));

        assertThat(registry.get("shadowfit.outbox.failed").gauge().value()).isEqualTo(2.0);
    }
}
