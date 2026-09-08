package com.shadowfit.service.exercise;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebClientAiAnalysisClient} 단위 테스트 — JDK 내장 {@link HttpServer}로 실제 HTTP
 * 왕복을 태운다(새 테스트 의존성 없이 진짜 직렬화·역직렬화·헤더·상태코드를 검증하기 위해).
 *
 * <p>목적은 {@code ai-server}의 판정 로직을 다시 검증하는 게 아니라 — 이 클래스가
 * (1) snake_case JSON을 정확히 만들고 읽는지, (2) {@code X-AI-Worker} 헤더에 올바른
 * routingKey를 싣는지, (3) HTTP 400을 {@link AiCallOutcome.ClientRejected}로,
 * 그 외 실패를 {@link AiCallOutcome.TransientFailure}로 정확히 나누는지 — 즉
 * {@link GrpcAiAnalysisClient}와 대칭인 "전송 계층"만 검증한다.
 */
@DisplayName("WebClientAiAnalysisClient 테스트")
class WebClientAiAnalysisClientTest {

    private HttpServer server;
    private WebClientAiAnalysisClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        client = new WebClientAiAnalysisClient();
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "internalToken", "test-internal-token");
        ReflectionTestUtils.setField(client, "channelPoolSize", 3);
        ReflectionTestUtils.invokeMethod(client, "initWebClient");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    @DisplayName("stopAnalysis 성공 — snake_case 응답을 StopResult로 정확히 역직렬화한다")
    void stopAnalysis_성공() {
        respond("/api/v1/internal/analysis/stop", 200,
                "{\"success\":true,\"message\":\"분석 중단 및 결과 보고 예약 완료.\"}");

        AiCallOutcome<AiAnalysisClient.StopResult> outcome =
                client.stopAnalysis(7L, new AiAnalysisClient.StopCommand(7L));

        assertThat(outcome).isInstanceOf(AiCallOutcome.Success.class);
        var result = ((AiCallOutcome.Success<AiAnalysisClient.StopResult>) outcome).value();
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("분석 중단 및 결과 보고 예약 완료.");
    }

    @Test
    @DisplayName("요청 바디가 snake_case JSON이고 X-AI-Worker 헤더가 routingKey % poolSize 다")
    void 요청_직렬화와_라우팅헤더() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedHeader = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        server.createContext("/api/v1/internal/analysis/start", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedHeader.set(exchange.getRequestHeaders().getFirst("X-AI-Worker"));
            byte[] bytes = "{\"session_id\":55}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            latch.countDown();
        });

        AiAnalysisClient.AnalyzeCommand command = new AiAnalysisClient.AnalyzeCommand(
                1L, 55L, "https://youtu.be/dummy",
                List.of(new AiAnalysisClient.PoseRef(0.0, "[]")), "BEGINNER", "nonce-1");

        client.startAnalysis(55L, command, outcome -> { });

        assertThat(latch.await(5, TimeUnit.SECONDS)).as("요청이 도달해야 한다").isTrue();
        // floorMod(55, 3) = 1
        assertThat(capturedHeader.get()).isEqualTo("1");
        assertThat(capturedBody.get())
                .contains("\"exercise_id\":1")
                .contains("\"session_id\":55")
                .contains("\"reference_source\":\"https://youtu.be/dummy\"")
                .contains("\"session_nonce\":\"nonce-1\"")
                .doesNotContain("exerciseId"); // camelCase가 새면 ai-server가 기본값으로 조용히 읽는다
    }

    @Test
    @DisplayName("HTTP 400 → ClientRejected — ai-server의 _AbortSignal(gRPC INVALID_ARGUMENT)과 대칭")
    void HTTP400은_ClientRejected() {
        respond("/api/v1/internal/analysis/start", 400, "{\"detail\":\"분석기가 없는 운동입니다\"}");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AiCallOutcome<AiAnalysisClient.AnalyzeResult>> captured = new AtomicReference<>();
        client.startAnalysis(1L, new AiAnalysisClient.AnalyzeCommand(
                2L, 1L, "x", List.of(), "BEGINNER", ""), outcome -> {
            captured.set(outcome);
            latch.countDown();
        });

        assertThat(awaitQuietly(latch)).isTrue();
        assertThat(captured.get()).isInstanceOf(AiCallOutcome.ClientRejected.class);
    }

    @Test
    @DisplayName("HTTP 503 → TransientFailure — 서킷브레이커가 실패로 기록해야 하는 쪽")
    void HTTP503은_TransientFailure() {
        respond("/api/v1/internal/analysis/stop", 503, "{\"detail\":\"unavailable\"}");

        AiCallOutcome<AiAnalysisClient.StopResult> outcome =
                client.stopAnalysis(1L, new AiAnalysisClient.StopCommand(1L));

        assertThat(outcome).isInstanceOf(AiCallOutcome.TransientFailure.class);
    }

    @Test
    @DisplayName("연결 자체가 안 되면 TransientFailure — 서버가 없는 포트로 요청한다")
    void 연결실패는_TransientFailure() {
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.invokeMethod(client, "initWebClient");

        AiCallOutcome<AiAnalysisClient.StopResult> outcome =
                client.stopAnalysis(1L, new AiAnalysisClient.StopCommand(1L));

        assertThat(outcome).isInstanceOf(AiCallOutcome.TransientFailure.class);
    }

    private boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
