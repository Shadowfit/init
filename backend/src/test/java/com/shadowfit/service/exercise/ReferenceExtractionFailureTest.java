package com.shadowfit.service.exercise;

import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.ExtractRequest;
import com.shadowfit.grpc.ExtractResponse;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기준 좌표 추출 요청이 AI 에 <b>접수조차 안 된</b> 경우를 종목 행에 남기는지(V34, 2026-10-21 결정 B).
 *
 * <p>AI 가 추출을 끝내고 역호출하는 경로(성공·실패 사유)는 {@code PoseDataServiceTest}·
 * {@code AdminConsoleIntegrationTest} 가 본다. 여기는 그 앞 — 접수 응답 {@code success=false}(파일 없음),
 * gRPC 오류, 서킷 OPEN — 이라 역호출이 영영 안 오는 경우다. 이걸 안 남기면 관리자 화면은 10분 동안 «추출 중» 이다.
 *
 * <p>콜백은 gRPC 이벤트 루프 스레드에서 돌아서 예외를 받을 사람이 없다 — 기록이 실패해도 예외가 새지 않는지까지 본다.
 * 원객체를 직접 조립하는 이유는 {@code SessionMetricsRecordingTest} 와 같다(그 분기만 정확히 때린다).
 */
@DisplayName("기준 좌표 추출 접수 실패 기록")
class ReferenceExtractionFailureTest {

    private static final long EXERCISE_ID = 1L;

    @Mock private SessionRepository sessionRepository;
    @Mock private ExercisesRepository exercisesRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private SessionService sessionService;
    @Mock private ExerciseReferenceRepository referenceRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ReattachRequestBuilder reattachRequestBuilder;
    @Mock private ReferenceExtractionStatusService statusService;

    private CircuitBreakerRegistry circuitBreakerRegistry;
    private ExerciseServiceGrpc.ExerciseServiceStub stub;
    private ExerciseAnalysisService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        stub = mock(ExerciseServiceGrpc.ExerciseServiceStub.class);
        when(stub.withInterceptors(any(io.grpc.ClientInterceptor[].class))).thenReturn(stub);
        when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);

        service = new ExerciseAnalysisService(sessionRepository, exercisesRepository,
                memberRepository, sessionService, referenceRepository,
                circuitBreakerRegistry, new SessionMetrics(new SimpleMeterRegistry()),
                outboxEventRepository, reattachRequestBuilder, statusService);
        ReflectionTestUtils.setField(service, "internalToken", "test-token");
        ReflectionTestUtils.setField(service, "aiChannelPoolSize", 1);
        ReflectionTestUtils.setField(service, "aiAsyncStubPool", List.of(stub));

        when(exercisesRepository.findByIdCached(EXERCISE_ID))
                .thenReturn(Optional.of(Exercise.builder().id(EXERCISE_ID).code("SQUAT").build()));
    }

    /** AI 응답을 흉내낸다 — 스텁이 받은 observer 에 {@code reply} 를 그대로 흘린다. */
    private void aiReplies(Consumer<StreamObserver<ExtractResponse>> reply) {
        doAnswer(invocation -> {
            StreamObserver<ExtractResponse> observer = invocation.getArgument(1);
            reply.accept(observer);
            return null;
        }).when(stub).extractReferenceData(any(ExtractRequest.class), any());
    }

    @Test
    @DisplayName("접수 응답 success=false(AI 가 파일을 못 찾음) → FAILED «공유 볼륨 확인»")
    void rejected_marksFailed() {
        aiReplies(o -> {
            o.onNext(ExtractResponse.newBuilder().setSuccess(false).setExerciseId(EXERCISE_ID).build());
            o.onCompleted();
        });

        service.extractReferencePoses(EXERCISE_ID, "/data/reference-videos/1/a.mp4");

        verify(statusService).markFailed(EXERCISE_ID, "AI 서버가 영상 파일을 찾지 못했어요 (공유 볼륨 확인).");
    }

    @Test
    @DisplayName("접수 응답 success=true → 아무것도 안 남긴다(결과는 역호출이 닫는다)")
    void accepted_leavesStatusToCallback() {
        aiReplies(o -> {
            o.onNext(ExtractResponse.newBuilder().setSuccess(true).setExerciseId(EXERCISE_ID).build());
            o.onCompleted();
        });

        service.extractReferencePoses(EXERCISE_ID, "/data/reference-videos/1/a.mp4");

        verify(statusService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("gRPC 오류 → FAILED «AI 서버와 통신하지 못했어요: 상태코드 — 설명»")
    void grpcError_marksFailed() {
        aiReplies(o -> o.onError(new StatusRuntimeException(
                io.grpc.Status.UNAVAILABLE.withDescription("connection refused"))));

        service.extractReferencePoses(EXERCISE_ID, "/data/reference-videos/1/a.mp4");

        verify(statusService).markFailed(EXERCISE_ID, "AI 서버와 통신하지 못했어요: UNAVAILABLE — connection refused");
    }

    @Test
    @DisplayName("기록 자체가 실패해도 콜백 밖으로 예외가 새지 않는다")
    void recordFailure_neverEscapesObserver() {
        doThrow(new IllegalStateException("db down")).when(statusService).markFailed(anyLong(), anyString());
        aiReplies(o -> o.onError(new StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED)));

        assertThatCode(() -> service.extractReferencePoses(EXERCISE_ID, "/data/reference-videos/1/a.mp4"))
                .doesNotThrowAnyException();
        verify(statusService).markFailed(EXERCISE_ID, "AI 서버와 통신하지 못했어요: DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("서킷 OPEN → 요청을 안 보내고 FAILED 로 남긴다")
    void circuitOpen_marksFailedWithoutCalling() {
        circuitBreakerRegistry.circuitBreaker("aiServer-0").transitionToOpenState();

        service.extractReferencePoses(EXERCISE_ID, "/data/reference-videos/1/a.mp4");

        verify(stub, never()).extractReferenceData(any(ExtractRequest.class), any());
        verify(statusService).markFailed(EXERCISE_ID,
                "AI 서버가 응답하지 않아 추출을 요청하지 못했어요. 잠시 후 영상을 다시 올려주세요.");
    }
}
