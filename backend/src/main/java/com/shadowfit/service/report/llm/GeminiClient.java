package com.shadowfit.service.report.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Gemini {@code generateContent} 한 번 — SDK 없이 REST(report-generation-llm.md §7 «인터페이스 뒤로», §14-0).
 * 모양은 {@code ExpoPushClient} 그대로: 전용 RestClient + 서킷브레이커 + 실패를 «다시 보내면 될 수 있다»
 * ({@link GeminiTransportException})와 «다시 보내도 같다»({@link GeminiRejectedException}) 둘로만 가른다.
 *
 * <p>출력은 JSON 스키마를 강제한다({@code responseMimeType} + {@code responseSchema}) — 잡담·서론이 토큰을
 * 먹지 않고, 파싱 실패가 곧 검증 실패다. 스키마·프롬프트는 {@link WeeklyReportPrompt} 가 갖는다.
 */
@Slf4j
@Component
public class GeminiClient {

    public static final String CIRCUIT_BREAKER = "gemini";

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final CircuitBreaker circuitBreaker;

    public GeminiClient(RestClient geminiRestClient, GeminiProperties properties,
                        CircuitBreakerRegistry circuitBreakerRegistry) {
        this.restClient = geminiRestClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String model() {
        return properties.getModel();
    }

    /**
     * @param systemInstruction 역할·금지 사항(한국어만, 계산 금지 …)
     * @param userText          입력 집계 JSON
     * @param responseSchema    Gemini 스키마 객체(OBJECT/STRING/NUMBER …)
     * @param temperature       낮을수록 같은 입력에 같은 문장 — 실측(2026-09-14)은 0.2 로 했다
     */
    public GeminiResult generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                                 double temperature) {
        if (!isEnabled()) {
            throw new GeminiRejectedException("llm.gemini.api-key 가 비어 있다 — LLM 비활성");
        }
        try {
            return circuitBreaker.executeSupplier(() -> post(systemInstruction, userText, responseSchema, temperature));
        } catch (CallNotPermittedException e) {
            throw new GeminiTransportException("Gemini 서킷 OPEN — 호출 생략", e);
        }
    }

    private GeminiResult post(String systemInstruction, String userText, Map<String, Object> responseSchema,
                              double temperature) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", responseSchema,
                        "temperature", temperature));
        GenerateContentResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getBaseUrl() + "/models/{model}:generateContent", properties.getModel())
                    // 키는 헤더로 — URL 쿼리에 실으면 접근 로그·프록시에 남는다.
                    .header("x-goog-api-key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GenerateContentResponse.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                // 무료 티어 분당·일일 한도 — 시간이 지나면 풀린다.
                throw new GeminiTransportException("Gemini rate limit(429)", e);
            }
            // 404(모델 사라짐)·400(스키마·키) — 설정을 고치기 전엔 다시 보내도 같다.
            throw new GeminiRejectedException("Gemini 가 요청을 거절함 — " + e.getStatusCode() + " "
                    + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            // 503 "high demand"(2026-09-14 실측에서 3.8-flash 가 11회 연속)·타임아웃 — 상대 사정이다.
            throw new GeminiTransportException("Gemini 에 닿지 못함 — " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new GeminiRejectedException("Gemini 응답 해석 실패 — " + e.getMessage(), e);
        }
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new GeminiRejectedException("Gemini 응답에 candidates 가 없음 — promptFeedback=" + response);
        }
        Candidate candidate = response.candidates().get(0);
        if (candidate.content() == null || candidate.content().parts() == null || candidate.content().parts().isEmpty()) {
            // 안전 필터 등으로 본문 없이 끝난 경우 — finishReason 만 있다. 재시도해도 같은 입력엔 같다.
            throw new GeminiRejectedException("Gemini 응답에 본문이 없음 — finishReason=" + candidate.finishReason());
        }
        UsageMetadata usage = response.usageMetadata();
        return new GeminiResult(candidate.content().parts().get(0).text(),
                response.modelVersion() != null ? response.modelVersion() : properties.getModel(),
                usage == null ? null : usage.promptTokenCount(),
                usage == null ? null : usage.candidatesTokenCount(),
                candidate.finishReason());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateContentResponse(List<Candidate> candidates, UsageMetadata usageMetadata, String modelVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount) {
    }
}
