package com.shadowfit.service.exercise;

import com.fasterxml.jackson.annotation.JsonRawValue;
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

    /**
     * 5차 라운드(docs/decisions/grpc-webclient-native-rest-round.md §2-1)의 세 REST 팔.
     * 전송 계층(WebClient·풀·타임아웃·인코더)은 셋이 완전히 같고 <b>경로 접두와 직렬화 규칙만</b>
     * 다르다 — 그래야 B−C(겹)·C−D(이중 인코딩) 뺄셈이 성립한다.
     * <ul>
     *   <li>{@code mirror} — 4차까지의 미러. AI 쪽이 JSON→pydantic→proto 재조립→gRPC 서비서.</li>
     *   <li>{@code native} — 같은 계약, AI 쪽이 pydantic 객체를 서비서에 바로 넘긴다.</li>
     *   <li>{@code nested} — {@code joint_coordinates} 를 문자열이 아니라 중첩 JSON 그대로 싣는다
     *       ({@link RawJointCoordinatesMixIn}). AI 쪽은 두 번째 파싱을 안 한다.</li>
     * </ul>
     */
    enum Contract {
        MIRROR("/api/v1/internal/analysis"),
        NATIVE("/api/v1/internal/analysis/native"),
        NESTED("/api/v1/internal/analysis/native-nested");

        final String prefix;

        Contract(String prefix) {
            this.prefix = prefix;
        }
    }

    /**
     * nested 계약 전용 — {@link AiAnalysisClient.PoseRef#jointCoordinates()} 는 DB 의
     * {@code pose_data.joint_coordinates} JSON 문자열인데, 기본 직렬화는 그걸 문자열로 한 번 더
     * 감싼다({@code "[{\"index\":0,...}]"}). {@code @JsonRawValue} 면 파싱 없이 그대로 박히므로
     * Spring 쪽 CPU 는 오히려 준다. 🔴 문자열이 유효 JSON 이 아니면 본문 전체가 깨진다 — 채택 시
     * 조건(설계 §2-3).
     */
    abstract static class RawJointCoordinatesMixIn {
        @JsonRawValue
        abstract String jointCoordinates();
    }

    // gRPC 쪽 GRPC_CALL_TIMEOUT_SECONDS와 같은 값 — 실측 튜닝된 값이 아닌 보수적 기본값이라
    // 두 구현체가 같은 상수를 쓰는 게 공정한 비교의 전제다.
    private static final long CALL_TIMEOUT_SECONDS = 5;

    @Value("${ai.webclient.base-url}")
    private String baseUrl;

    @Value("${internal.api.token}")
    private String internalToken;

    @Value("${ai.channel-pool-size:3}")
    private int channelPoolSize;

    @Value("${ai.webclient.contract:mirror}")
    private String contractName;

    private Contract contract;
    private WebClient webClient;

    // ai-server의 Pydantic 모델(app/models/pose.py 등)이 이미 snake_case + proto 필드명
    // 그대로를 쓴다 — 이 WebClient만 그 관례를 따르도록 ObjectMapper를 따로 둔다(Spring↔
    // 프론트의 camelCase JSON에는 영향 없음, 이 빈 하나에만 적용).
    @PostConstruct
    private void initWebClient() {
        contract = Contract.valueOf(contractName.trim().toUpperCase());
        ObjectMapper snakeCaseMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        if (contract == Contract.NESTED) {
            snakeCaseMapper.addMixIn(PoseRef.class, RawJointCoordinatesMixIn.class);
        }
        log.info("WebClientAiAnalysisClient contract={} prefix={}", contract, contract.prefix);

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
                .uri(contract.prefix + "/extract-reference")
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
                .uri(contract.prefix + "/start")
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
                    .uri(contract.prefix + "/reattach")
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
                    .uri(contract.prefix + "/stop")
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
