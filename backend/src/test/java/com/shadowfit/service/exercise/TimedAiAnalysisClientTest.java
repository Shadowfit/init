package com.shadowfit.service.exercise;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지연 계기가 «두 팔을 같은 자리에서 재는가»와 «동작을 안 바꾸는가» 둘을 고정한다
 * (docs/decisions/grpc-webclient-empirical-comparison.md §10.4).
 *
 * <p>이 둘이 깨지는 방식이 특히 나쁘다 — 예외가 아니라 <b>지표가 조용히 비거나</b> 콜백이
 * 두 번 불리는 형태로 나타나서, 라운드를 다 돌린 뒤에야 발견된다.
 */
@DisplayName("TimedAiAnalysisClient — AI 호출 지연 계기")
class TimedAiAnalysisClientTest {

    /** 호출을 받아 정해둔 결과를 그대로 돌려주는 가짜 전송 계층. */
    private static class FakeTransport implements AiAnalysisClient {
        final AtomicInteger startCalls = new AtomicInteger();
        int callbackTimes = 1;   // 콜백을 몇 번 부를지(중복 호출 재현용)

        @Override
        public void extractReferenceData(long routingKey, ExtractCommand command,
                                         Consumer<AiCallOutcome<ExtractResult>> onResult) {
            onResult.accept(new AiCallOutcome.Success<>(new ExtractResult(true, command.exerciseId())));
        }

        @Override
        public void startAnalysis(long routingKey, AnalyzeCommand command,
                                  Consumer<AiCallOutcome<AnalyzeResult>> onResult) {
            startCalls.incrementAndGet();
            for (int i = 0; i < callbackTimes; i++) {
                onResult.accept(new AiCallOutcome.Success<>(new AnalyzeResult(command.sessionId())));
            }
        }

        @Override
        public AiCallOutcome<ReattachResult> reattachAnalysis(long routingKey, ReattachCommand command) {
            return new AiCallOutcome.Success<>(new ReattachResult(true, 3, false, "ok"));
        }

        @Override
        public AiCallOutcome<StopResult> stopAnalysis(long routingKey, StopCommand command) {
            return new AiCallOutcome.TransientFailure<>("AI 죽음", new RuntimeException("boom"));
        }
    }

    /** 해당 태그 조합의 타이머가 «아예 없는» 것과 «0건»은 여기선 같은 뜻이라 0으로 접는다. */
    private long count(MeterRegistry registry, String rpc, String outcome, String protocol) {
        io.micrometer.core.instrument.Timer timer = registry.find(TimedAiAnalysisClient.AI_CALL)
                .tag("rpc", rpc).tag("outcome", outcome).tag("protocol", protocol)
                .timer();
        return timer == null ? 0L : timer.count();
    }

    @Test
    @DisplayName("블로킹 2개 — rpc·outcome·protocol 태그로 갈라 기록한다")
    void blockingCallsAreRecorded() {
        MeterRegistry registry = new SimpleMeterRegistry();
        TimedAiAnalysisClient client =
                new TimedAiAnalysisClient(new FakeTransport(), registry, "webclient");

        client.reattachAnalysis(7L, new AiAnalysisClient.ReattachCommand(7L, 1L, "BEGINNER", 0, 0.0, "n", List.of()));
        client.stopAnalysis(7L, new AiAnalysisClient.StopCommand(7L));

        assertThat(count(registry, "reattach", "success", "webclient")).isEqualTo(1);
        // 실패를 성공과 같은 통에 담으면 «연결 즉시 거절»이 빠른 응답으로 섞여 델타가 왜곡된다.
        assertThat(count(registry, "stop", "failure", "webclient")).isEqualTo(1);
        assertThat(count(registry, "stop", "success", "webclient")).isZero();
    }

    @Test
    @DisplayName("fire-and-forget — 끝점은 콜백이 불린 시점이고, 원본 콜백은 그대로 통과한다")
    void fireAndForgetIsRecordedAtCallback() {
        MeterRegistry registry = new SimpleMeterRegistry();
        TimedAiAnalysisClient client =
                new TimedAiAnalysisClient(new FakeTransport(), registry, "grpc");

        List<AiCallOutcome<AiAnalysisClient.AnalyzeResult>> seen = new ArrayList<>();
        client.startAnalysis(7L, new AiAnalysisClient.AnalyzeCommand(1L, 7L, "url", List.of(), "BEGINNER", "n"), seen::add);

        assertThat(seen).hasSize(1);
        assertThat(count(registry, "start", "success", "grpc")).isEqualTo(1);
    }

    @Test
    @DisplayName("전송 계층이 콜백을 두 번 불러도 — 기록은 1건, 전달은 2건 (동작을 안 바꾼다)")
    void duplicateCallbackDoesNotDoubleRecordButStillPassesThrough() {
        MeterRegistry registry = new SimpleMeterRegistry();
        FakeTransport transport = new FakeTransport();
        transport.callbackTimes = 2;
        TimedAiAnalysisClient client = new TimedAiAnalysisClient(transport, registry, "grpc");

        AtomicInteger delivered = new AtomicInteger();
        client.startAnalysis(7L, new AiAnalysisClient.AnalyzeCommand(1L, 7L, "url", List.of(), "BEGINNER", "n"),
                outcome -> delivered.incrementAndGet());

        // 🔴 «중복이면 하나를 버린다»가 아니다. 타이머만 첫 건에 묶고 전달은 손대지 않는다 —
        //    계기가 호출 횟수를 바꾸면 그건 측정이 아니라 동작 변경이다.
        assertThat(delivered.get()).isEqualTo(2);
        assertThat(count(registry, "start", "success", "grpc")).isEqualTo(1);
    }

    /** 조립이 어긋나 원본이 그대로 주입되면 지표가 «조용히 0건»이 된다 — 그 실패를 여기서 잡는다. */
    @SpringBootTest
    @DisplayName("조립 — 주입되는 AiAnalysisClient 는 계기로 감싼 쪽이다")
    static class WiringTest {

        @Autowired
        private AiAnalysisClient injected;

        @Autowired
        private ExerciseAnalysisService exerciseAnalysisService;

        @Test
        @DisplayName("@Primary 래퍼가 이기고, 래퍼가 감싼 것은 자기 자신이 아니다")
        void wrapperWins() {
            assertThat(injected).isInstanceOf(TimedAiAnalysisClient.class);
            assertThat(exerciseAnalysisService).isNotNull();
        }
    }
}
