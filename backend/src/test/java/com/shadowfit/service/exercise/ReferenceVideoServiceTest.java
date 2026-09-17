package com.shadowfit.service.exercise;

import com.shadowfit.dto.admin.AdminExerciseDetailDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.ExercisesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReferenceVideoService} 의 <b>순서와 보상</b> — 세 자원(서킷·파일·DB) 사이의 경계가 주제다.
 *
 * <p>파일·DB 는 전부 대역이다. 여기서 보는 것은 «무엇이 무엇보다 먼저인가» 와 «실패하면 무엇을 되돌리나» 이지
 * 실제 저장이 아니다 — 그건 {@code ReferenceVideoUploadIntegrationTest} 가 본다.
 */
@DisplayName("ReferenceVideoService — 파일·DB·gRPC 경계")
class ReferenceVideoServiceTest {

    @Mock private ExercisesRepository exercisesRepository;
    @Mock private AdminExerciseService adminExerciseService;
    @Mock private ExerciseAnalysisService analysisService;
    @Mock private ReferenceVideoStorage storage;

    private ReferenceVideoService service;
    private final MockMultipartFile file =
            new MockMultipartFile("file", "squat.mp4", "video/mp4", new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'});

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReferenceVideoService(exercisesRepository, adminExerciseService, analysisService, storage);
        when(exercisesRepository.findByIdCached(1L)).thenReturn(Optional.of(mock(Exercise.class)));
        when(analysisService.isAiReachable(1L)).thenReturn(true);
    }

    @Test
    @DisplayName("없는 운동이면 404 — 파일도 서킷도 안 본다")
    void notFound_touchesNothing() {
        when(exercisesRepository.findByIdCached(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(99L, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);

        verifyNoInteractions(storage, adminExerciseService);
    }

    @Test
    @DisplayName("서킷 OPEN 이면 503 — 파일·DB 를 건드리기 전에 끝난다 (confirm ㄴ)")
    void circuitOpen_rejectsBeforeAnyWrite() {
        when(analysisService.isAiReachable(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.upload(1L, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFERENCE_EXTRACTION_UNAVAILABLE);

        verifyNoInteractions(storage, adminExerciseService);
    }

    @Test
    @DisplayName("DB 가 실패하면 방금 쓴 파일을 지운다 (보상) — 예외는 그대로 올라간다")
    void dbFailure_deletesJustWrittenFile() {
        when(storage.store(1L, file)).thenReturn("1/new.mp4");
        when(adminExerciseService.attachReferenceVideo(eq(1L), eq("1/new.mp4"), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.upload(1L, file))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(storage).deleteQuietly("1/new.mp4");
        verify(analysisService, never()).extractReferencePoses(any(), any());
    }

    @Test
    @DisplayName("커밋 뒤 훅: AI 에는 ai-dir 경로로 요청하고, 이전 파일만 지운다")
    @SuppressWarnings("unchecked")
    void afterCommitHook_firesGrpcAndDeletesPrevious() {
        when(storage.store(1L, file)).thenReturn("1/new.mp4");
        when(storage.aiPath("1/new.mp4")).thenReturn("/data/reference-videos/1/new.mp4");
        AdminExerciseDetailDto dto = mock(AdminExerciseDetailDto.class);
        when(adminExerciseService.attachReferenceVideo(eq(1L), eq("1/new.mp4"), any())).thenReturn(dto);

        assertThat(service.upload(1L, file)).isSameAs(dto);

        ArgumentCaptor<Consumer<String>> hook = ArgumentCaptor.forClass(Consumer.class);
        verify(adminExerciseService).attachReferenceVideo(eq(1L), eq("1/new.mp4"), hook.capture());
        // 트랜잭션 메서드가 던지지 않았으므로 여기까지 gRPC 는 안 나갔다 — 커밋 뒤에만 나간다.
        verify(analysisService, never()).extractReferencePoses(any(), any());

        hook.getValue().accept("1/old.mp4");

        verify(analysisService).extractReferencePoses(1L, "/data/reference-videos/1/new.mp4");
        verify(storage).deleteQuietly("1/old.mp4");
        verify(storage, never()).deleteQuietly("1/new.mp4");
    }

    @Test
    @DisplayName("이전 영상이 없었으면(첫 업로드) 지울 게 없다")
    @SuppressWarnings("unchecked")
    void afterCommitHook_firstUpload_deletesNothing() {
        when(storage.store(1L, file)).thenReturn("1/new.mp4");
        when(storage.aiPath("1/new.mp4")).thenReturn("/data/reference-videos/1/new.mp4");
        when(adminExerciseService.attachReferenceVideo(eq(1L), eq("1/new.mp4"), any()))
                .thenReturn(mock(AdminExerciseDetailDto.class));

        service.upload(1L, file);

        ArgumentCaptor<Consumer<String>> hook = ArgumentCaptor.forClass(Consumer.class);
        verify(adminExerciseService).attachReferenceVideo(eq(1L), eq("1/new.mp4"), hook.capture());
        hook.getValue().accept(null);

        verify(storage, never()).deleteQuietly(any());
    }
}
