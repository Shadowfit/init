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

        client = newClient("mirror");
    }

    /** 계약 팔(mirror|native|nested)만 다른 클라이언트 — 5차 라운드 §2-1 의 세 REST 팔. */
    private WebClientAiAnalysisClient newClient(String contract) {
        WebClientAiAnalysisClient c = new WebClientAiAnalysisClient();
        ReflectionTestUtils.setField(c, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(c, "internalToken", "test-internal-token");
        ReflectionTestUtils.setField(c, "channelPoolSize", 3);
        ReflectionTestUtils.setField(c, "contractName", contract);
        ReflectionTestUtils.invokeMethod(c, "initWebClient");
        return c;
    }

    /** 경로 하나에서 요청 본문을 잡아 돌려준다 — 계약 팔 테스트 둘이 같이 쓴다. */
    private AtomicReference<String> captureReattach(String path) {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext(path, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"success\":true,\"rep_count\":3,\"already_active\":true,\"message\":\"\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return body;
    }

    private static AiAnalysisClient.ReattachCommand reattachWithOneLandmark() {
        return new AiAnalysisClient.ReattachCommand(
                9L, 1L, "BEGINNER", 3, 12.5, "nonce-9",
                List.of(new AiAnalysisClient.PoseRef(0.0, "[{\"index\":0,\"x\":0.1,\"y\":0.2}]")));
    }

    @Test
    @DisplayName("native 계약 — 경로만 /native 로 바뀌고 본문은 mirror 와 같다(문자열 안의 문자열)")
    void native_계약은_경로만_다르다() {
        AtomicReference<String> body = captureReattach("/api/v1/internal/analysis/native/reattach");

        AiCallOutcome<AiAnalysisClient.ReattachResult> outcome =
                newClient("native").reattachAnalysis(9L, reattachWithOneLandmark());

        assertThat(outcome).isInstanceOf(AiCallOutcome.Success.class);
        // joint_coordinates 는 여전히 JSON 문자열 — 따옴표가 이스케이프돼 있다.
        assertThat(body.get()).contains("\"joint_coordinates\":\"[{\\\"index\\\":0");
    }

    @Test
    @DisplayName("nested 계약 — joint_coordinates 가 문자열이 아니라 중첩 JSON 그대로 실린다(@JsonRawValue)")
    void nested_계약은_중첩JSON을_그대로_싣는다() {
        AtomicReference<String> body = captureReattach("/api/v1/internal/analysis/native-nested/reattach");

        AiCallOutcome<AiAnalysisClient.ReattachResult> outcome =
                newClient("nested").reattachAnalysis(9L, reattachWithOneLandmark());

        assertThat(outcome).isInstanceOf(AiCallOutcome.Success.class);
        assertThat(body.get())
                .contains("\"joint_coordinates\":[{\"index\":0,\"x\":0.1,\"y\":0.2}]")
                .doesNotContain("\\\""); // 이스케이프된 따옴표가 하나라도 있으면 문자열로 감싼 것
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
