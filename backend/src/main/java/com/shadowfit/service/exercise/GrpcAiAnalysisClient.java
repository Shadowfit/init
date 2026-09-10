package com.shadowfit.service.exercise;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.GrpcCorrelationClientInterceptor;
import com.shadowfit.grpc.AnalyzeRequest;
import com.shadowfit.grpc.AnalyzeResponse;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.ExtractRequest;
import com.shadowfit.grpc.ExtractResponse;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.ReattachRequest;
import com.shadowfit.grpc.ReattachResponse;
import com.shadowfit.grpc.StopRequest;
import com.shadowfit.grpc.StopResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link AiAnalysisClient}의 gRPC 구현체 — {@code ExerciseAnalysisService}에 있던 채널 풀·인증·
 * 라우팅 로직을 그대로 옮겼다(docs/decisions/grpc-webclient-empirical-comparison.md §8).
 *
 * <p><b>여기 없는 것 — 의도적이다(발견 3).</b> 서킷브레이커 기록(permission/success/error)과
 * 세션 업무 반응({@code markAsFailedIfStillInProgress} 등)은 이 클래스가 하지 않는다. 호출자
 * ({@code ExerciseAnalysisService})가 {@link AiCallOutcome}을 보고 결정한다 — 이 클래스는
 * 전송(요청 조립 → 호출 → 응답/에러를 {@code AiCallOutcome}으로 정규화)만 맡는다.
 *
 * <p>🔴 <b>워커별 서킷브레이커 등록</b>({@code circuitBreakerRegistry.circuitBreaker("aiServer-"+i)})과
 * "서킷 OPEN → 재부착 큐잉" 자동 반응({@code enqueueReattachForWorker})은 아직 {@code
 * ExerciseAnalysisService}에 그대로 남아 있다 — 옮기지 않았다. 이건 "AI 프로세스가 몇 개고
 * 어느 게 죽었나"라는 워커 헬스 개념이라 gRPC 채널 풀 존재 여부와 무관하게 필요하다(WebClient로
 * 가도 nginx가 여전히 워커 i로 라우팅하므로 워커별 서킷 개념 자체는 유효하다, §8.3). 다만 그
 * 등록 코드가 지금 {@code initAiChannelPool}의 {@code @PostConstruct} 타이밍에 얹혀 있던 걸
 * 어디로 떼어내는 게 맞는지는 아직 확인이 필요한 지점이라 손대지 않았다.
 */
@Component
// TimedAiAnalysisClient(@Primary)가 이 빈을 감싼다 — 한정자가 있어야 래퍼가
// 자기 자신이 아니라 이 구현체를 고른다.
@Qualifier("aiTransport")
@ConditionalOnProperty(name = "ai.client-type", havingValue = "grpc", matchIfMissing = true)
@Slf4j
public class GrpcAiAnalysisClient implements AiAnalysisClient {

    @Value("${internal.api.token}")
    private String internalToken;

    // 풀 크기는 상수가 아니라 ai.channel-pool-size(= docker-compose 의 AI_WORKER_COUNT 와 같은
    // 소스)에서 읽는다 — entrypoint.sh 가 띄우는 실제 워커 수와 손으로 맞출 필요가 없다
    // (docs/decisions/ai-channel-pool-hardening.md).
    @Value("${ai.channel-pool-size:3}")
    private int channelPoolSize;

    @Value("${grpc.client.fastapi-client.address}")
    private String fastApiAddress; // "static://host:port" 형식

    private final List<ManagedChannel> channelPool = new ArrayList<>();
    private final List<ExerciseServiceGrpc.ExerciseServiceStub> asyncStubPool = new ArrayList<>();
    private final List<ExerciseServiceGrpc.ExerciseServiceBlockingStub> blockingStubPool = new ArrayList<>();

    // 상태가 없어 인스턴스 하나를 공유해도 안전하다.
    private static final GrpcCorrelationClientInterceptor CORRELATION_INTERCEPTOR =
            new GrpcCorrelationClientInterceptor();

    // AI가 죽지 않고 그냥 응답을 안 주는(hang) 경우, 데드라인 없이는 onNext/onError 둘 다 안
    // 불려서 호출자가 그 호출을 영원히 실패/느림으로 못 잡는다. 전부 "빠른 ack" 성격의 제어
    // 호출이라 5초로 통일(실측 튜닝된 값 아닌 보수적 기본값).
    private static final long GRPC_CALL_TIMEOUT_SECONDS = 5;

    // 🔴 채널 인덱스 i는 반드시 gRPC 포트 base+i 와 짝을 맞춰야 한다(실측 2026-08-26 —
    // 안 맞추면 채널 여러 개가 전부 같은 워커로만 몰리는 버그가 난 이력이 있다).
    @PostConstruct
    private void initChannelPool() {
        String hostPort = fastApiAddress.replaceFirst("^static://", "");
        String host = hostPort.substring(0, hostPort.lastIndexOf(':'));
        int basePort = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));
        for (int i = 0; i < channelPoolSize; i++) {
            int port = basePort + i;
            ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            channelPool.add(channel);
            asyncStubPool.add(ExerciseServiceGrpc.newStub(channel));
            blockingStubPool.add(ExerciseServiceGrpc.newBlockingStub(channel));
            log.info("AI gRPC 채널[{}] 초기화 완료 (대상: {}:{})", i, host, port);
        }
    }

    @PreDestroy
    private void shutdownChannelPool() {
        for (int i = 0; i < channelPool.size(); i++) {
            ManagedChannel ch = channelPool.get(i);
            ch.shutdownNow();
            try {
                if (!ch.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("AI gRPC 채널[{}] 3초 내 종료 안 됨 — 그냥 넘어간다", i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("AI gRPC 채널[{}] 종료 대기 중 인터럽트", i);
            }
        }
        log.info("AI gRPC 채널 풀 {}개 종료 완료", channelPool.size());
    }

    private Metadata authHeader() {
        Metadata header = new Metadata();
        Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        header.put(authKey, "Bearer " + internalToken);
        return header;
    }

    private ExerciseServiceGrpc.ExerciseServiceStub authenticatedAsyncStub(long routingKey) {
        return asyncStubPool.get(Math.floorMod(routingKey, channelPoolSize))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authHeader()), CORRELATION_INTERCEPTOR)
                .withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private ExerciseServiceGrpc.ExerciseServiceBlockingStub authenticatedBlockingStub(long routingKey) {
        return blockingStubPool.get(Math.floorMod(routingKey, channelPoolSize))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authHeader()), CORRELATION_INTERCEPTOR)
                .withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * gRPC {@code INVALID_ARGUMENT}만 "내 잘못"(ClientRejected)으로 본다 — {@code UNAVAILABLE}·
     * {@code DEADLINE_EXCEEDED} 등은 건강 신호로 남겨야 하므로 TransientFailure.
     */
    private <T> AiCallOutcome<T> classify(Throwable t) {
        if (t instanceof StatusRuntimeException e
                && e.getStatus().getCode() == io.grpc.Status.Code.INVALID_ARGUMENT) {
            return new AiCallOutcome.ClientRejected<>(t.getMessage(), t);
        }
        return new AiCallOutcome.TransientFailure<>(t.getMessage(), t);
    }

    @Override
    public void extractReferenceData(long routingKey, ExtractCommand command,
                                      Consumer<AiCallOutcome<ExtractResult>> onResult) {
        ExtractRequest request = ExtractRequest.newBuilder()
                .setExerciseId(command.exerciseId())
                .setYoutubeUrl(command.youtubeUrl())
                .build();

        // preserving(): 콜백은 gRPC 이벤트루프 스레드에서 실행돼 호출자 MDC가 없다 — 감싸지
        // 않으면 실패 로그(onError)에 correlation id가 안 붙는다.
        authenticatedAsyncStub(routingKey).extractReferenceData(
                request, CorrelationIds.preserving(new StreamObserver<ExtractResponse>() {
                    @Override
                    public void onNext(ExtractResponse value) {
                        onResult.accept(new AiCallOutcome.Success<>(
                                new ExtractResult(value.getSuccess(), value.getExerciseId())));
                    }

                    @Override
                    public void onError(Throwable t) {
                        onResult.accept(classify(t));
                    }

                    @Override
                    public void onCompleted() {
                    }
                }));
    }

    @Override
    public void startAnalysis(long routingKey, AnalyzeCommand command,
                               Consumer<AiCallOutcome<AnalyzeResult>> onResult) {
        AnalyzeRequest.Builder requestBuilder = AnalyzeRequest.newBuilder()
                .setExerciseId(command.exerciseId())
                .setSessionId(command.sessionId())
                .setReferenceSource(command.referenceSource())
                .setPersona(command.persona())
                // proto3라 null은 못 싣는다. 빈 문자열이 곧 "없음"이고 AI는 compat 통과로 읽는다.
                .setSessionNonce(command.sessionNonce() == null ? "" : command.sessionNonce());
        for (PoseRef ref : command.referencePoses()) {
            requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(ref.timestampSec())
                    .setJointCoordinates(ref.jointCoordinates())
                    .build());
        }

        authenticatedAsyncStub(routingKey).startAnalysis(
                requestBuilder.build(), CorrelationIds.preserving(new StreamObserver<AnalyzeResponse>() {
                    @Override
                    public void onNext(AnalyzeResponse value) {
                        onResult.accept(new AiCallOutcome.Success<>(new AnalyzeResult(value.getSessionId())));
                    }

                    @Override
                    public void onError(Throwable t) {
                        onResult.accept(classify(t));
                    }

                    @Override
                    public void onCompleted() {
                    }
                }));
    }

    @Override
    public AiCallOutcome<ReattachResult> reattachAnalysis(long routingKey, ReattachCommand command) {
        ReattachRequest.Builder requestBuilder = ReattachRequest.newBuilder()
                .setSessionId(command.sessionId())
                .setExerciseId(command.exerciseId())
                .setPersona(command.persona())
                .setInitialRepCount(command.initialRepCount())
                .setElapsedSec(command.elapsedSec())
                .setSessionNonce(command.sessionNonce() == null ? "" : command.sessionNonce());
        for (PoseRef ref : command.referencePoses()) {
            requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(ref.timestampSec())
                    .setJointCoordinates(ref.jointCoordinates())
                    .build());
        }

        try {
            ReattachResponse response =
                    authenticatedBlockingStub(routingKey).reattachAnalysis(requestBuilder.build());
            return new AiCallOutcome.Success<>(new ReattachResult(
                    response.getSuccess(), response.getRepCount(),
                    response.getAlreadyActive(), response.getMessage()));
        } catch (StatusRuntimeException e) {
            return classify(e);
        }
    }

    @Override
    public AiCallOutcome<StopResult> stopAnalysis(long routingKey, StopCommand command) {
        StopRequest request = StopRequest.newBuilder().setSessionId(command.sessionId()).build();
        try {
            StopResponse response = authenticatedBlockingStub(routingKey).stopAnalysis(request);
            return new AiCallOutcome.Success<>(new StopResult(response.getSuccess(), response.getMessage()));
        } catch (RuntimeException e) {
            // gRPC 실패는 StatusRuntimeException 으로 오지만, 인터셉터·직렬화 등 그 바깥에서 나는
            // 예외도 있다 — 원래 stopAnalysis 도 이걸 넓게 잡아 "예외를 던지지 않는다"는 호출부
            // 계약을 지켰다. 여기서도 같은 폭으로 잡는다.
            return classify(e);
        }
    }
}
