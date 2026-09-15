package com.shadowfit.service.notification.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Expo Push API 클라이언트 — 메시지 묶음 하나를 한 요청으로 보내고 티켓 목록을 돌려준다
 * (social-cheer-and-group-feed.md §4-3 ⑤·⑥·⑧).
 *
 * <p><b>실패를 두 갈래로만 나눈다.</b> 닿지 못함·429·5xx·서킷 OPEN 은 {@link ExpoPushTransportException}
 * (나중엔 될 수 있다), 그 외 4xx·규격 밖 응답은 {@link ExpoPushRejectedException}(다시 보내도 같다).
 * 티켓 단위 오류는 예외가 아니라 반환값이다 — 그 분류는 {@link PushDispatchService} 가 한다.
 *
 * <p><b>서킷브레이커가 붙는 이유</b>는 AI 채널과 같은 자리의 같은 문제다: 발행기가 배치 20행을
 * 순서대로 보내는데 Expo 가 죽어 매번 타임아웃(5s)까지 기다리면 20×5s = 100s 로 선점 lease(60s)를
 * 넘는다 — 자기 행을 {@code claimStale} 이 회수해 중복 송신이 난다. 서킷이 OPEN 이면 즉시
 * {@link ExpoPushTransportException} 으로 빠져 이 창이 안 열린다. 인스턴스 이름은 하나
 * ({@value #CIRCUIT_BREAKER}) — AI 처럼 워커별로 나눌 상대가 아니다.
 */
@Slf4j
@Component
public class ExpoPushClient {

    public static final String CIRCUIT_BREAKER = "expoPush";

    /** Expo 규격의 요청당 메시지 상한. 넘기면 요청이 거절된다. */
    public static final int MAX_MESSAGES_PER_REQUEST = 100;

    private final RestClient restClient;
    private final ExpoPushProperties properties;
    private final CircuitBreaker circuitBreaker;

    public ExpoPushClient(RestClient expoRestClient, ExpoPushProperties properties,
                          CircuitBreakerRegistry circuitBreakerRegistry) {
        this.restClient = expoRestClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    /**
     * 메시지 묶음을 보낸다. 티켓은 <b>메시지와 같은 순서·같은 개수</b>로 돌아온다(Expo 규격).
     *
     * @throws ExpoPushTransportException 닿지 못함·429·5xx·서킷 OPEN — 재시도 대상
     * @throws ExpoPushRejectedException  그 외 4xx·규격 밖 응답 — 재시도 무의미
     */
    public List<ExpoPushTicket> send(List<ExpoPushMessage> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        if (messages.size() > MAX_MESSAGES_PER_REQUEST) {
            throw new ExpoPushRejectedException("요청당 메시지 상한 초과: " + messages.size());
        }
        try {
            return circuitBreaker.executeSupplier(() -> post(messages));
        } catch (CallNotPermittedException e) {
            throw new ExpoPushTransportException("Expo 서킷 OPEN — 송신 생략", e);
        }
    }

    private List<ExpoPushTicket> post(List<ExpoPushMessage> messages) {
        ExpoPushResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(this::authorize)
                    .body(messages)
                    .retrieve()
                    .body(ExpoPushResponse.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new ExpoPushTransportException("Expo rate limit(429)", e);
            }
            throw new ExpoPushRejectedException("Expo 가 요청을 거절함 — " + e.getStatusCode() + " "
                    + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new ExpoPushTransportException("Expo 에 닿지 못함 — " + e.getMessage(), e);
        } catch (RestClientException e) {
            // 응답 본문을 못 읽은 경우 등 — 규격 밖 응답이라 다시 보내도 같다.
            throw new ExpoPushRejectedException("Expo 응답 해석 실패 — " + e.getMessage(), e);
        }
        if (response == null || response.data() == null) {
            throw new ExpoPushRejectedException("Expo 응답에 data 가 없음 — errors=" + describe(response));
        }
        if (response.data().size() != messages.size()) {
            throw new ExpoPushRejectedException("티켓 수(" + response.data().size() + ")가 메시지 수("
                    + messages.size() + ")와 다름");
        }
        return response.data();
    }

    private void authorize(HttpHeaders headers) {
        String token = properties.getAccessToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    private static String describe(ExpoPushResponse response) {
        return response == null || response.errors() == null ? "(없음)" : response.errors().toString();
    }

    /** Expo 응답 껍데기 — 성공이면 {@code data}, 요청 단위 오류면 {@code errors}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpoPushResponse(List<ExpoPushTicket> data, List<RequestError> errors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RequestError(String code, String message) {
    }
}
