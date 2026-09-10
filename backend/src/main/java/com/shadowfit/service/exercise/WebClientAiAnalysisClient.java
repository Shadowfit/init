package com.shadowfit.service.exercise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * {@link AiAnalysisClient}의 WebClient 구현체 — Spring→AI 요청 방향 RPC 4개를
 * {@code ai-nginx}(8000) 뒤의 REST 미러(`/internal/analysis/*`, ai-server)로 보낸다
 * (docs/decisions/grpc-webclient-empirical-comparison.md §8).
 *
 * <p><b>{@link GrpcAiAnalysisClient}와 거울 관계다</b> — 같은 4개 계약, 같은
 * {@link AiCallOutcome} 정규화, 같은 라우팅 키 의미(sessionId/exerciseId). 다른 건 딱 두 가지:
 * (1) gRPC 채널 풀이 nginx를 건너뛰고 8585-8587에 직결하는 것과 달리, 여기는 프론트의
 * {@code POST /pose}가 이미 쓰는 {@code X-AI-Worker} 헤더 기반 nginx 라우팅을 그대로
 * 재사용한다(§8.3) — 그래서 이 클래스엔 gRPC 쪽의 수동 채널 풀·포트 매핑 코드가 없다.
 * (2) 인증이 gRPC 메타데이터 대신 HTTP {@code Authorization: Bearer} 헤더다(§8.2,
 * ai-server의 {@code InternalAuthMiddleware}가 이 경로만 INTERNAL_API_TOKEN으로 지킨다).
 *
 * <p>fire-and-forget 2개({@link #extractReferenceData}/{@link #startAnalysis})는
 * {@code .subscribe()}로 쏘고 응답을 기다리지 않는다 — 호출 스레드는 구독 시점에 바로
 * 반납되고, 결과는 Reactor Netty 이벤트루프 스레드에서 콜백으로 온다(gRPC async 스텁의
 * {@code StreamObserver}와 같은 모양). 블로킹 2개({@link #reattachAnalysis}/
 * {@link #stopAnalysis})는 {@code .block()}을 쓴다 — 이건 타협이 아니라 원래 설계 의도다:
 * 두 호출부 모두 "블로킹 비용이 사실상 0인 스레드"(사용자 요청 스레드·아웃박스 발행기 전용
 * 스레드)에서만 불린다는 전제가 이미 깔려 있다.
 */
@Component
// TimedAiAnalysisClient(@Primary)가 이 빈을 감싼다 — 한정자가 있어야 래퍼가
// 자기 자신이 아니라 이 구현체를 고른다.
@Qualifier("aiTransport")
@ConditionalOnProperty(name = "ai.client-type", havingValue = "webclient")
@Slf4j
public class WebClientAiAnalysisClient implements AiAnalysisClient {

    private static final String EXTRACT_PATH = "/api/v1/internal/analysis/extract-reference";
    private static final String START_PATH = "/api/v1/internal/analysis/start";
    private static final String REATTACH_PATH = "/api/v1/internal/analysis/reattach";
    private static final String STOP_PATH = "/api/v1/internal/analysis/stop";

    // gRPC 쪽 GRPC_CALL_TIMEOUT_SECONDS와 같은 값 — 실측 튜닝된 값이 아닌 보수적 기본값이라
    // 두 구현체가 같은 상수를 쓰는 게 공정한 비교의 전제다.
    private static final long CALL_TIMEOUT_SECONDS = 5;

    @Value("${ai.webclient.base-url}")
    private String baseUrl;

    @Value("${internal.api.token}")
    private String internalToken;

    @Value("${ai.channel-pool-size:3}")
    private int channelPoolSize;

    private WebClient webClient;

    // ai-server의 Pydantic 모델(app/models/pose.py 등)이 이미 snake_case + proto 필드명
    // 그대로를 쓴다 — 이 WebClient만 그 관례를 따르도록 ObjectMapper를 따로 둔다(Spring↔
    // 프론트의 camelCase JSON에는 영향 없음, 이 빈 하나에만 적용).
    @PostConstruct
    private void initWebClient() {
        ObjectMapper snakeCaseMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(snakeCaseMapper, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(snakeCaseMapper, MediaType.APPLICATION_JSON));
                })
                .build();

        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + internalToken)
                .exchangeStrategies(strategies)
                .build();
    }

    private String workerHeader(long routingKey) {
        return String.valueOf(Math.floorMod(routingKey, channelPoolSize));
    }

    /**
     * HTTP 400만 "내 잘못"(ClientRejected)으로 본다 — ai-server의 {@code _AbortSignal}이
     * gRPC {@code INVALID_ARGUMENT}를 그렇게 옮긴다(§8.2). 그 외(5xx·타임아웃·연결 실패)는
     * TransientFailure — {@link GrpcAiAnalysisClient#classify}와 대칭.
     */
    private <T> AiCallOutcome<T> classify(Throwable t) {
        if (t instanceof WebClientResponseException e && e.getStatusCode().value() == 400) {
            return new AiCallOutcome.ClientRejected<>(e.getResponseBodyAsString(), e);
        }
        return new AiCallOutcome.TransientFailure<>(t.getMessage(), t);
    }

    @Override
    public void extractReferenceData(long routingKey, ExtractCommand command,
                                      Consumer<AiCallOutcome<ExtractResult>> onResult) {
        webClient.post()
                .uri(EXTRACT_PATH)
                .header("X-AI-Worker", workerHeader(routingKey))
                .bodyValue(command)
                .retrieve()
                .bodyToMono(ExtractResult.class)
                .timeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
                .subscribe(
                        result -> onResult.accept(new AiCallOutcome.Success<>(result)),
                        t -> onResult.accept(classify(t))
                );
    }

    @Override
    public void startAnalysis(long routingKey, AnalyzeCommand command,
                               Consumer<AiCallOutcome<AnalyzeResult>> onResult) {
        webClient.post()
                .uri(START_PATH)
                .header("X-AI-Worker", workerHeader(routingKey))
                .bodyValue(command)
                .retrieve()
                .bodyToMono(AnalyzeResult.class)
                .timeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
                .subscribe(
                        result -> onResult.accept(new AiCallOutcome.Success<>(result)),
                        t -> onResult.accept(classify(t))
                );
    }

    @Override
    public AiCallOutcome<ReattachResult> reattachAnalysis(long routingKey, ReattachCommand command) {
        try {
            ReattachResult result = webClient.post()
                    .uri(REATTACH_PATH)
                    .header("X-AI-Worker", workerHeader(routingKey))
                    .bodyValue(command)
                    .retrieve()
                    .bodyToMono(ReattachResult.class)
                    .timeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
                    .block();
            return new AiCallOutcome.Success<>(result);
        } catch (RuntimeException e) {
            return classify(e);
        }
    }

    @Override
    public AiCallOutcome<StopResult> stopAnalysis(long routingKey, StopCommand command) {
        try {
            StopResult result = webClient.post()
                    .uri(STOP_PATH)
                    .header("X-AI-Worker", workerHeader(routingKey))
                    .bodyValue(command)
                    .retrieve()
                    .bodyToMono(StopResult.class)
                    .timeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
                    .block();
            return new AiCallOutcome.Success<>(result);
        } catch (RuntimeException e) {
            return classify(e);
        }
    }
}
