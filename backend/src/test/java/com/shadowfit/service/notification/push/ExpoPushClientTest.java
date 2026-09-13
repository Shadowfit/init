package com.shadowfit.service.notification.push;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Expo 클라이언트의 실패 두 갈래(전송 실패 vs 거절)를 HTTP 응답별로 고정한다. 실제 exp.host 는 안 친다 —
 * {@link MockRestServiceServer} 가 {@link RestClient} 를 가로챈다.
 */
@DisplayName("ExpoPushClient")
class ExpoPushClientTest {

    private static final String URL = "http://expo.test/push/send";

    private MockRestServiceServer server;
    private ExpoPushProperties properties;
    private CircuitBreakerRegistry registry;
    private ExpoPushClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new ExpoPushProperties();
        properties.setUrl(URL);
        registry = CircuitBreakerRegistry.ofDefaults();
        client = new ExpoPushClient(builder.build(), properties, registry);
    }

    private static List<ExpoPushMessage> twoMessages() {
        return List.of(
                ExpoPushMessage.of("ExponentPushToken[aaa]", "t", "b", Map.of("notificationId", 1)),
                ExpoPushMessage.of("ExponentPushToken[bbb]", "t", "b", Map.of("notificationId", 1)));
    }

    @Test
    @DisplayName("200 — 티켓을 메시지 순서대로 돌려준다, 접근 토큰이 비면 Authorization 헤더 없음")
    void ok_ticketsInOrder_noAuthHeaderWhenBlank() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(jsonPath("$[0].to").value("ExponentPushToken[aaa]"))
                .andExpect(jsonPath("$[0].sound").value("default"))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"x1\"},"
                        + "{\"status\":\"error\",\"message\":\"gone\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<ExpoPushTicket> tickets = client.send(twoMessages());

        assertThat(tickets).hasSize(2);
        assertThat(tickets.get(0).ok()).isTrue();
        assertThat(tickets.get(1).ok()).isFalse();
        assertThat(tickets.get(1).errorCode()).isEqualTo(ExpoPushTicket.DEVICE_NOT_REGISTERED);
        server.verify();
    }

    @Test
    @DisplayName("접근 토큰이 있으면 Bearer 헤더를 붙인다")
    void bearerHeaderWhenTokenSet() {
        properties.setAccessToken("secret");
        server.expect(requestTo(URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\"},{\"status\":\"ok\"}]}",
                        MediaType.APPLICATION_JSON));

        client.send(twoMessages());
        server.verify();
    }

    @Test
    @DisplayName("429 → 전송 실패(재시도 대상)")
    void tooManyRequests_isTransport() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushTransportException.class);
    }

    @Test
    @DisplayName("5xx → 전송 실패(재시도 대상)")
    void serverError_isTransport() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushTransportException.class);
    }

    @Test
    @DisplayName("연결 실패(I/O) → 전송 실패(재시도 대상)")
    void ioError_isTransport() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection refused")));
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushTransportException.class);
    }

    @Test
    @DisplayName("400·401 등 429 아닌 4xx → 거절(재시도 무의미)")
    void clientError_isRejected() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"errors\":[{\"code\":\"INVALID_CREDENTIALS\"}]}"));
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushRejectedException.class);
    }

    @Test
    @DisplayName("200 인데 티켓 수가 메시지 수와 다르면 거절 — 어느 티켓이 어느 토큰인지 알 수 없다")
    void ticketCountMismatch_isRejected() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushRejectedException.class);
    }

    @Test
    @DisplayName("200 인데 data 없이 errors 만 오면 거절")
    void errorsOnly_isRejected() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"errors\":[{\"code\":\"PUSH_TOO_MANY_EXPERIENCE_IDS\",\"message\":\"x\"}]}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushRejectedException.class);
    }

    @Test
    @DisplayName("서킷 OPEN 이면 HTTP 없이 즉시 전송 실패 — lease 안에서 배치를 끝내는 장치")
    void circuitOpen_failsFastWithoutHttp() {
        registry.circuitBreaker(ExpoPushClient.CIRCUIT_BREAKER).transitionToOpenState();
        // 요청 기대를 안 건다 — 하나라도 나가면 MockRestServiceServer 가 AssertionError 를 던진다.
        assertThatThrownBy(() -> client.send(twoMessages())).isInstanceOf(ExpoPushTransportException.class);
        server.verify();
    }

    @Test
    @DisplayName("빈 묶음은 요청 없이 빈 목록, 100 초과는 거절")
    void emptyAndOversize() {
        assertThat(client.send(List.of())).isEmpty();
        List<ExpoPushMessage> tooMany = IntStream.range(0, ExpoPushClient.MAX_MESSAGES_PER_REQUEST + 1)
                .mapToObj(i -> ExpoPushMessage.of("ExponentPushToken[" + i + "]", "t", "b", Map.of()))
                .toList();
        assertThatThrownBy(() -> client.send(tooMany)).isInstanceOf(ExpoPushRejectedException.class);
        server.verify();
    }
}
