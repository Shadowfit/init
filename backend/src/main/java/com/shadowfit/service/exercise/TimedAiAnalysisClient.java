package com.shadowfit.service.exercise;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link AiAnalysisClient} 구현체를 감싸 <b>Spring→AI 요청 RPC 1회의 왕복 시간</b>을 재는
 * 데코레이터. 두 팔(grpc·webclient) 중 무엇이 뜨든 <b>문자 그대로 같은 경계</b>에서 재는 것이
 * 존재 이유다(docs/decisions/grpc-webclient-empirical-comparison.md §10.4).
 *
 * <p><b>왜 호출부가 아니라 여기인가.</b> {@code ExerciseAnalysisService}는 이미 호출마다
 * {@code System.nanoTime()}을 재지만 그 값은 <b>서킷브레이커에만</b> 먹인다 — 지표로는 안
 * 나간다. 그 자리에 기록을 얹으면 같은 코드가 RPC 4곳 × 성공/거절/장애 3분기에 흩어지고,
 * 한 분기를 빠뜨리면 두 팔의 모수가 달라져 <b>델타 자체가 오염된다.</b> 여기서 한 번 감싸면
 * 그 위험이 없다.
 *
 * <p><b>fire-and-forget 2개의 끝점은 «콜백이 불린 시점»이다.</b> {@code extractReferenceData}·
 * {@code startAnalysis}는 호출 스레드가 응답을 안 기다리므로(계약은 {@link AiAnalysisClient}
 * 참고) 메서드 반환 시점을 재면 «큐에 넣는 데 걸린 시간»만 나온다. 그래서 콜백을 한 겹 감싸
 * 결과가 도착한 순간을 끝점으로 삼는다.
 *
 * <p>🔴 <b>감싼 콜백은 원본 콜백의 호출 횟수를 바꾸지 않는다.</b> 타이머 기록만
 * {@link AtomicBoolean}로 첫 호출에 묶고, 전달 자체는 매번 그대로 통과시킨다 — 측정하려다
 * 동작을 바꾸는 것이 제일 나쁘기 때문이다({@code TimedAiAnalysisClientTest}가 고정한다).
 *
 * <p>주입은 {@link Primary}로 이 빈이 이기고, 이 빈이 감싸는 원본은 {@code @Qualifier("aiTransport")}
 * 로 고른다 — 두 구현체에만 그 한정자가 붙어 있어 자기 자신을 감쌀 수 없다.
 */
@Component
@Primary
public class TimedAiAnalysisClient implements AiAnalysisClient {

    /** 팔(protocol)·RPC·결과(outcome)로 가르는 왕복 지연. 백분위까지 발행한다 — 평균은 꼬리를 못 본다. */
    static final String AI_CALL = "shadowfit.ai.call";

    private final AiAnalysisClient delegate;
    private final MeterRegistry registry;
    private final String protocol;

    public TimedAiAnalysisClient(@Qualifier("aiTransport") AiAnalysisClient delegate,
                                 MeterRegistry registry,
                                 @Value("${ai.client-type:grpc}") String protocol) {
        this.delegate = delegate;
        this.registry = registry;
        this.protocol = protocol;
    }

    @Override
    public void extractReferenceData(long routingKey, ExtractCommand command,
                                     Consumer<AiCallOutcome<ExtractResult>> onResult) {
        long start = System.nanoTime();
        delegate.extractReferenceData(routingKey, command, timed("extract", start, onResult));
    }

    @Override
    public void startAnalysis(long routingKey, AnalyzeCommand command,
                              Consumer<AiCallOutcome<AnalyzeResult>> onResult) {
        long start = System.nanoTime();
        delegate.startAnalysis(routingKey, command, timed("start", start, onResult));
    }

    @Override
    public AiCallOutcome<ReattachResult> reattachAnalysis(long routingKey, ReattachCommand command) {
        long start = System.nanoTime();
        AiCallOutcome<ReattachResult> outcome = delegate.reattachAnalysis(routingKey, command);
        record("reattach", start, outcome);
        return outcome;
    }

    @Override
    public AiCallOutcome<StopResult> stopAnalysis(long routingKey, StopCommand command) {
        long start = System.nanoTime();
        AiCallOutcome<StopResult> outcome = delegate.stopAnalysis(routingKey, command);
        record("stop", start, outcome);
        return outcome;
    }

    private <T> Consumer<AiCallOutcome<T>> timed(String rpc, long start, Consumer<AiCallOutcome<T>> onResult) {
        AtomicBoolean recorded = new AtomicBoolean(false);
        return outcome -> {
            if (recorded.compareAndSet(false, true)) {
                record(rpc, start, outcome);
            }
            onResult.accept(outcome);
        };
    }

    private void record(String rpc, long start, AiCallOutcome<?> outcome) {
        long elapsed = System.nanoTime() - start;
        Timer.builder(AI_CALL)
                .description("Spring→AI 요청 RPC 1회 왕복(요청 조립 시작 → 결과 도착)")
                .tag("protocol", protocol)
                .tag("rpc", rpc)
                .tag("outcome", outcomeTag(outcome))
                // 백분위와 «버킷» 을 같이 낸다. 백분위는 대시보드용이고, 버킷이 있어야
                // 블록 시작/끝을 차분해 «그 블록의 분포» 를 만들 수 있다 — 누적 카운터인
                // 백분위 값만으로는 블록이 안 갈린다(라운드 설계
                // grpc-webclient-production-client-round.md §5).
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                // 이 구간은 ms 단위다(1차 라운드 실측 0.4~3ms). 범위를 안 묶으면 버킷이
                // 초 단위까지 깔려 시계열만 늘어난다.
                .minimumExpectedValue(java.time.Duration.ofNanos(100_000))   // 100µs — Duration 에 ofMicros 는 없다
                .maximumExpectedValue(java.time.Duration.ofSeconds(5))
                .register(registry)
                .record(elapsed, TimeUnit.NANOSECONDS);
    }

    /**
     * 실패도 팔 비교에서 빼지 않고 태그로 가른다 — 실패가 성공보다 빠른(연결 즉시 거절) 경우가
     * 있어 한 통에 담으면 델타가 왜곡된다. 이름은 {@link AiCallOutcome}의 세 갈래를 그대로 쓴다.
     */
    private String outcomeTag(AiCallOutcome<?> outcome) {
        return switch (outcome) {
            case AiCallOutcome.Success<?> ignored -> "success";
            case AiCallOutcome.ClientRejected<?> ignored -> "rejected";
            case AiCallOutcome.TransientFailure<?> ignored -> "failure";
        };
    }
}
