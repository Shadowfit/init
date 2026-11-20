package com.shadowfit.global.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 스케줄러 «마지막 성공 시각» 게이지 — 성공 tick 에서만 갱신되고, 등록 직후에는 기동 시각이라는 것을 고정한다.
 * 시계를 주입해 «갱신됐는가» 를 초 단위로 가른다.
 */
@DisplayName("스케줄러 지표 — 마지막 성공 시각")
class SchedulerMetricsTest {

    private static final String GAUGE = "shadowfit.scheduler.last.success.timestamp";

    /** 테스트가 직접 움직이는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SessionMetrics metrics = new SessionMetrics(registry, clock);

    private double gauge(String scheduler) {
        return registry.get(GAUGE).tag("scheduler", scheduler).gauge().value();
    }

    @Test
    @DisplayName("등록 직후 값은 기동 시각 — 첫 tick 전에도 «마지막 성공 이후 경과» 를 계산할 수 있다")
    void registered_startsAtStartupTime() {
        metrics.registerScheduler("s1");

        assertEquals(clock.instant().getEpochSecond(), gauge("s1"));
    }

    @Test
    @DisplayName("success tick 만 시각을 올린다 — partial·error 는 올리지 않는다")
    void onlySuccessAdvancesLastSuccess() {
        metrics.registerScheduler("s1");
        double startup = gauge("s1");

        clock.advanceSeconds(60);
        metrics.schedulerTick("s1", "error");
        metrics.schedulerTick("s1", "partial");
        assertEquals(startup, gauge("s1"), "실패·부분 실패가 마지막 성공을 갱신하면 알람이 걸리지 않는다");

        metrics.schedulerTick("s1", "success");
        assertEquals(startup + 60, gauge("s1"));
    }

    @Test
    @DisplayName("tick 은 결과별로 센다")
    void ticksAreCountedByOutcome() {
        metrics.registerScheduler("s1");
        metrics.schedulerTick("s1", "success");
        metrics.schedulerTick("s1", "success");
        metrics.schedulerTick("s1", "error");

        assertEquals(2.0, registry.counter("shadowfit.scheduler.tick", "scheduler", "s1", "outcome", "success").count());
        assertEquals(1.0, registry.counter("shadowfit.scheduler.tick", "scheduler", "s1", "outcome", "error").count());
    }

    @Test
    @DisplayName("스케줄러마다 따로 센다 — 이름 태그로 구분")
    void schedulersAreIndependent() {
        metrics.registerScheduler("a");
        metrics.registerScheduler("b");
        clock.advanceSeconds(30);
        metrics.schedulerTick("a", "success");

        assertEquals(clock.instant().getEpochSecond(), gauge("a"));
        assertEquals(clock.instant().getEpochSecond() - 30, gauge("b"));
    }

    @Test
    @DisplayName("등록을 두 번 해도 값이 되돌아가지 않는다")
    void registerTwice_isIdempotent() {
        metrics.registerScheduler("s1");
        clock.advanceSeconds(10);
        metrics.schedulerTick("s1", "success");
        double after = gauge("s1");

        metrics.registerScheduler("s1");

        assertEquals(after, gauge("s1"));
    }

    @Test
    @DisplayName("Prometheus 로 나가는 이름 — 알람 규칙이 거는 이름을 고정한다")
    void exportedNames_areWhatAlertRulesWillUse() {
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry prom =
                new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                        io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        SessionMetrics m = new SessionMetrics(prom, clock);
        m.registerScheduler("session-timeout");
        m.schedulerTick("session-timeout", "success");

        String scrape = prom.scrape();

        // 알람 예: time() - shadowfit_scheduler_last_success_timestamp_seconds{scheduler="session-timeout"} > 300
        org.junit.jupiter.api.Assertions.assertTrue(
                scrape.contains("shadowfit_scheduler_last_success_timestamp_seconds{"), scrape);
        org.junit.jupiter.api.Assertions.assertTrue(
                scrape.contains("shadowfit_scheduler_tick_total{"), scrape);
    }

    @Test
    @DisplayName("등록하지 않은 이름으로 success 가 와도 값이 생긴다")
    void successWithoutRegister_createsGauge() {
        metrics.schedulerTick("late", "success");

        assertEquals(clock.instant().getEpochSecond(), gauge("late"));
    }
}
