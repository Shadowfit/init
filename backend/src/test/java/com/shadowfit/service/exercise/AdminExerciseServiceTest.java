package com.shadowfit.service.exercise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.AdminExerciseDetailDto;
import com.shadowfit.dto.admin.ExerciseCreateDto;
import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ExerciseUpdateDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExerciseQueryRepository;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AdminExerciseService 테스트")
class AdminExerciseServiceTest {

    @Mock private ExercisesRepository exercisesRepository;
    @Mock private ExerciseQueryRepository exerciseQueryRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private ExerciseReferenceRepository exerciseReferenceRepository;
    @Mock private CategoryRepository categoryRepository;
    private AdminExerciseService service;

    private static final Long EXERCISE_ID = 1L;
    // Mockito 단위테스트라 실제로 저장하지 않는다 — id 를 직접 박아 "이미 존재하는 카테고리"를
    // 흉내낸다. categoryRepository.findById 스텁이 이 id 로 되돌려준다(아래 setUp).
    private static final Category category = Category.builder().id(10L).name("LOWER").build();
    private static final Category categoryBack = Category.builder().id(20L).name("BACK").build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AdminExerciseService(exercisesRepository, exerciseQueryRepository,
                sessionRepository, exerciseReferenceRepository, categoryRepository, new ObjectMapper());
        // lenient — Update 쪽 일부 테스트는 categoryId 를 안 보내 이 스텁을 안 탄다.
        org.mockito.Mockito.lenient().when(categoryRepository.findById(categoryBack.getId()))
                .thenReturn(Optional.of(categoryBack));
        org.mockito.Mockito.lenient().when(categoryRepository.findById(category.getId()))
                .thenReturn(Optional.of(category));
    }

    private Exercise exercise() {
        // code 가 있는 «분석 가능한 종목» 이 기본 픽스처다 — 켜기(W020) 검사가 걸리지 않게.
        return Exercise.builder().id(EXERCISE_ID).name("스쿼트").code("SQUAT").category(category)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .syncThresholdDiet(new BigDecimal("70.00")).syncThresholdRehab(new BigDecimal("50.00"))
                .build();
    }

    @Nested
    @DisplayName("임계값 변경")
    class UpdateThresholds {

        @Test
        @DisplayName("정상 변경 — 4개 임계값 전부 갱신")
        void updateThresholds_success() {
            Exercise exercise = exercise();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            ThresholdUpdateDto dto = new ThresholdUpdateDto(
                    new BigDecimal("55"), new BigDecimal("90"), new BigDecimal("65"), new BigDecimal("45"));

            ExerciseThresholdResponseDto result = service.updateThresholds(EXERCISE_ID, dto);

            assertThat(result.syncThresholdBeginner()).isEqualByComparingTo(new BigDecimal("55"));
            assertThat(result.syncThresholdAdvanced()).isEqualByComparingTo(new BigDecimal("90"));
            assertThat(exercise.getSyncThresholdDiet()).isEqualByComparingTo(new BigDecimal("65"));
        }

        @Test
        @DisplayName("beginner >= advanced 이면 INVALID_INPUT_VALUE, 저장 시도 자체를 안 함")
        void updateThresholds_beginnerNotLessThanAdvanced_throws() {
            ThresholdUpdateDto dto = new ThresholdUpdateDto(
                    new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("65"), new BigDecimal("45"));

            assertThatThrownBy(() -> service.updateThresholds(EXERCISE_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

            // beginner==advanced 케이스라 findById까지 안 가고 검증에서 바로 걸려야 함
            org.mockito.Mockito.verifyNoInteractions(exercisesRepository);
        }

        @Test
        @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
        void updateThresholds_exerciseNotFound_throws() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.empty());
            ThresholdUpdateDto dto = new ThresholdUpdateDto(
                    new BigDecimal("55"), new BigDecimal("90"), new BigDecimal("65"), new BigDecimal("45"));

            assertThatThrownBy(() -> service.updateThresholds(EXERCISE_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("등록")
    class Create {

        /**
         * [왜 이 테스트가 필요한가] {@code analysisSupported} 를 서버가 고정한다는 것은 <b>DTO 에
         * 자리가 없다</b>는 사실로만 보장된다. 나중에 누가 편의를 위해 필드를 하나 늘리면 컴파일도
         * 통과하고 리뷰에서도 눈에 안 띄는데, 그 순간 분석기 없는 종목의 세션이 열린다(W007 우회).
         */
        @Test
        @DisplayName("analysisSupported 는 요청과 무관하게 false 로 저장된다")
        void create_analysisSupportedIsAlwaysFalse() {
            when(exercisesRepository.saveAndFlush(any(Exercise.class))).thenAnswer(inv -> inv.getArgument(0));
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "데드리프트", null, categoryBack.getId(), "설명", "https://y.com/x", null, null);

            AdminExerciseDetailDto result = service.createExercise(dto);

            assertThat(result.analysisSupported()).isFalse();
        }

        @Test
        @DisplayName("예상 운동시간을 생략하면 엔티티 기본값 15 가 남는다")
        void create_nullDuration_keepsEntityDefault() {
            when(exercisesRepository.saveAndFlush(any(Exercise.class))).thenAnswer(inv -> inv.getArgument(0));
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "데드리프트", null, categoryBack.getId(), null, null, null, null);

            AdminExerciseDetailDto result = service.createExercise(dto);

            // 빌더에 null 을 넘기면 @Builder.Default 가 덮여 NOT NULL 위반이 된다. 그걸 막는
            // 조건 분기가 살아 있는지 고정한다.
            assertThat(result.expectedDurationMinutes()).isEqualTo(15);
        }

        @Test
        @DisplayName("임계값 4종은 엔티티 기본값에서 시작한다")
        void create_thresholdsStartFromDefaults() {
            when(exercisesRepository.saveAndFlush(any(Exercise.class))).thenAnswer(inv -> inv.getArgument(0));
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "데드리프트", null, categoryBack.getId(), null, null, null, 20);

            AdminExerciseDetailDto result = service.createExercise(dto);

            assertThat(result.syncThresholdBeginner()).isEqualByComparingTo("60.00");
            assertThat(result.syncThresholdAdvanced()).isEqualByComparingTo("85.00");
            assertThat(result.expectedDurationMinutes()).isEqualTo(20);
        }

        @Test
        @DisplayName("code 를 보내면 그대로 저장되고, 생략하면 null(분석기 없는 종목)")
        void create_codeIsOptional() {
            when(exercisesRepository.saveAndFlush(any(Exercise.class))).thenAnswer(inv -> inv.getArgument(0));
            when(exercisesRepository.existsByCode("DEADLIFT")).thenReturn(false);

            AdminExerciseDetailDto withCode = service.createExercise(new ExerciseCreateDto(
                    "데드리프트", "DEADLIFT", categoryBack.getId(), null, null, null, null));
            AdminExerciseDetailDto withoutCode = service.createExercise(new ExerciseCreateDto(
                    "케틀벨 스윙", null, categoryBack.getId(), null, null, null, null));

            assertThat(withCode.code()).isEqualTo("DEADLIFT");
            assertThat(withoutCode.code()).isNull();
            // null 코드는 중복 검사 대상이 아니다 — UNIQUE 에서 NULL 끼리는 충돌하지 않는다
            verify(exercisesRepository, never()).existsByCode(null);
        }

        /**
         * [왜] 코드 하나 = 분석기 하나. UNIQUE 가 어차피 막지만 그러면 500 이다 — 관리자가 «뭐가 틀렸는지»
         * 를 보려면 저장 전에 W018 로 걸러야 한다.
         */
        @Test
        @DisplayName("이미 있는 code 면 EXERCISE_CODE_DUPLICATION — 저장 시도 자체를 안 함")
        void create_duplicateCode_throwsBeforeSave() {
            when(exercisesRepository.existsByCode("SQUAT")).thenReturn(true);
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "스쿼트2", "SQUAT", categoryBack.getId(), null, null, null, null);

            assertThatThrownBy(() -> service.createExercise(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_CODE_DUPLICATION);

            verify(exercisesRepository, never()).saveAndFlush(any());
        }

        /**
         * [왜] 사전 검사와 INSERT 사이에 같은 코드가 먼저 들어오면 UNIQUE 가 던진다 — 그걸 500 으로 흘리면
         * 관리자는 «뭐가 틀렸는지」 를 못 본다. 이름을 확인한 제약(uk_exercises_code)만 W018 로 접고,
         * 다른 무결성 위반은 서버 결함일 수 있어 그대로 던진다.
         */
        @Test
        @DisplayName("사전검사 뒤 UNIQUE(uk_exercises_code) 경합이면 W018 — 다른 제약 위반은 그대로")
        void create_uniqueRace_translatesOnlyCodeConstraint() {
            when(exercisesRepository.existsByCode("SQUAT")).thenReturn(false);
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "스쿼트2", "SQUAT", categoryBack.getId(), null, null, null, null);

            when(exercisesRepository.saveAndFlush(any(Exercise.class))).thenThrow(
                    new org.springframework.dao.DataIntegrityViolationException("dup",
                            new org.hibernate.exception.ConstraintViolationException("dup", null, "uk_exercises_code")));
            assertThatThrownBy(() -> service.createExercise(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_CODE_DUPLICATION);

            when(exercisesRepository.saveAndFlush(any(Exercise.class))).thenThrow(
                    new org.springframework.dao.DataIntegrityViolationException("fk",
                            new org.hibernate.exception.ConstraintViolationException("fk", null, "fk_exercises_category")));
            assertThatThrownBy(() -> service.createExercise(dto))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        /**
         * [왜] {@code target_joints} 는 MySQL {@code json} 컬럼이라 깨진 문자열을 DB 가 거부한다
         * (에러 3140). 걸러내지 않으면 400 이어야 할 것이 500 으로 나간다.
         */
        @Test
        @DisplayName("targetJoints 가 JSON 이 아니면 INVALID_INPUT_VALUE — 저장 시도 자체를 안 함")
        void create_invalidJson_throwsBeforeSave() {
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "데드리프트", null, categoryBack.getId(), null, null, "{깨진", null);

            assertThatThrownBy(() -> service.createExercise(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

            verify(exercisesRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("보낸 필드만 바뀌고 생략한 필드는 그대로 남는다")
        void update_onlySentFieldsChange() {
            Exercise exercise = exercise();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            ExerciseUpdateDto dto = new ExerciseUpdateDto(
                    "스쿼트(개선)", null, null, null, null, null, null);

            AdminExerciseDetailDto result = service.updateExercise(EXERCISE_ID, dto);

            assertThat(result.name()).isEqualTo("스쿼트(개선)");
            // category 를 안 보냈으니 그대로여야 한다 — null 로 덮으면 NOT NULL 위반이다
            assertThat(result.categoryId()).isEqualTo(category.getId());
            assertThat(result.expectedDurationMinutes()).isEqualTo(15);
        }

        @Test
        @DisplayName("name 을 빈 문자열로 보내면 INVALID_INPUT_VALUE")
        void update_blankName_throws() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise()));
            ExerciseUpdateDto dto = new ExerciseUpdateDto("   ", null, null, null, null, null, null);

            assertThatThrownBy(() -> service.updateExercise(EXERCISE_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("분석이 꺼진 종목은 code 를 바꿀 수 있다 — 자기 행은 중복 검사에서 제외")
        void update_code_whenDisabled_changes() {
            Exercise exercise = exercise(); // analysisSupported=false
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            when(exercisesRepository.existsByCodeAndIdNot("SQUAT_V2", EXERCISE_ID)).thenReturn(false);

            AdminExerciseDetailDto result = service.updateExercise(EXERCISE_ID,
                    new ExerciseUpdateDto(null, "SQUAT_V2", null, null, null, null, null));

            assertThat(result.code()).isEqualTo("SQUAT_V2");
        }

        @Test
        @DisplayName("같은 code 를 다시 보내면 no-op — 중복 검사도 타지 않는다")
        void update_sameCode_isNoop() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise()));

            AdminExerciseDetailDto result = service.updateExercise(EXERCISE_ID,
                    new ExerciseUpdateDto(null, "SQUAT", null, null, null, null, null));

            assertThat(result.code()).isEqualTo("SQUAT");
            verify(exercisesRepository, never()).existsByCodeAndIdNot(any(), any());
        }

        /**
         * [왜] 코드는 ai-server 가 분석기를 고르는 키다. 켜진 채 바꾸면 그 순간부터의 세션이 다른 분석기
         * 기준으로 채점되는데 기준 좌표는 옛 종목 것이다 — «조용히 틀린 점수» 가 #147 이 없애려던 바로 그것.
         */
        @Test
        @DisplayName("분석이 켜진 종목의 code 변경은 EXERCISE_CODE_LOCKED — 값이 그대로 남는다")
        void update_code_whenEnabled_throws() {
            Exercise exercise = exercise();
            exercise.changeAnalysisSupport(true);
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));

            assertThatThrownBy(() -> service.updateExercise(EXERCISE_ID,
                    new ExerciseUpdateDto(null, "LUNGE", null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_CODE_LOCKED);

            assertThat(exercise.getCode()).isEqualTo("SQUAT");
        }

        @Test
        @DisplayName("다른 행이 쓰는 code 로 바꾸면 EXERCISE_CODE_DUPLICATION")
        void update_duplicateCode_throws() {
            Exercise exercise = exercise();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            when(exercisesRepository.existsByCodeAndIdNot("LUNGE", EXERCISE_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.updateExercise(EXERCISE_ID,
                    new ExerciseUpdateDto(null, "LUNGE", null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_CODE_DUPLICATION);

            assertThat(exercise.getCode()).isEqualTo("SQUAT");
        }

        @Test
        @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
        void update_notFound_throws() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.empty());
            ExerciseUpdateDto dto = new ExerciseUpdateDto("x", null, null, null, null, null, null);

            assertThatThrownBy(() -> service.updateExercise(EXERCISE_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("분석 활성화")
    class AnalysisSupport {

        /**
         * [왜] 기준 좌표는 분석의 실제 입력이다({@code ExerciseAnalysisService:215}). 비어 있으면
         * ai-server 가 <b>경고만 하고 진행해</b> sync_rate 가 전부 0 이 된다
         * ({@code exercise_servicer.py:78-82}) — 즉 "켜졌는데 결과가 전부 0" 이라는, 실패로도
         * 안 보이는 상태가 만들어진다.
         */
        @Test
        @DisplayName("기준 좌표가 0건이면 켤 수 없다 — W012")
        void enable_withoutReferences_throws() {
            Exercise exercise = exercise();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            when(exerciseReferenceRepository.existsByExerciseId(EXERCISE_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.updateAnalysisSupport(EXERCISE_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_ANALYSIS_ENABLE_BLOCKED);

            // 거부됐으면 값이 그대로여야 한다 — 예외를 던지기 전에 setter 가 돌면 안 된다
            assertThat(exercise.getAnalysisSupported()).isFalse();
        }

        /**
         * [왜] 코드가 없으면 ai-server 는 어느 분석기를 쓸지 모른다 — StartAnalysis 가 거절돼 세션이 FAILED 로
         * 닫힌다(#147 ㄷ). 켜기 전에 막아야 «켰는데 세션마다 실패» 가 안 생긴다. 코드 검사가 기준 좌표보다
         * 먼저다 — 아래 verifyNoInteractions 가 그 순서를 고정한다.
         */
        @Test
        @DisplayName("code 가 없으면 켤 수 없다 — W020, 기준 좌표는 보지도 않는다")
        void enable_withoutCode_throws() {
            Exercise exercise = Exercise.builder().id(EXERCISE_ID).name("케틀벨 스윙").category(category).build();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));

            assertThatThrownBy(() -> service.updateAnalysisSupport(EXERCISE_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_CODE_REQUIRED);

            assertThat(exercise.getAnalysisSupported()).isFalse();
            verifyNoInteractions(exerciseReferenceRepository);
        }

        @Test
        @DisplayName("기준 좌표가 있으면 켜진다")
        void enable_withReferences_succeeds() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise()));
            when(exerciseReferenceRepository.existsByExerciseId(EXERCISE_ID)).thenReturn(true);

            AdminExerciseDetailDto result = service.updateAnalysisSupport(EXERCISE_ID, true);

            assertThat(result.analysisSupported()).isTrue();
        }

        /**
         * [왜] 끄는 방향은 안전하다. 기준 좌표가 없다는 이유로 <b>끄는 것까지</b> 막으면, 잘못
         * 켜진 종목을 되돌릴 수단이 사라진다.
         */
        @Test
        @DisplayName("끌 때는 기준 좌표를 보지 않는다")
        void disable_doesNotCheckReferences() {
            Exercise exercise = exercise();
            exercise.changeAnalysisSupport(true);
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));

            AdminExerciseDetailDto result = service.updateAnalysisSupport(EXERCISE_ID, false);

            assertThat(result.analysisSupported()).isFalse();
            verifyNoInteractions(exerciseReferenceRepository);
        }

        @Test
        @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
        void notFound_throws() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateAnalysisSupport(EXERCISE_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("세션 이력이 없으면 삭제된다")
        void delete_noSessions_deletes() {
            Exercise exercise = exercise();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            when(sessionRepository.existsByExerciseId(EXERCISE_ID)).thenReturn(false);

            service.deleteExercise(EXERCISE_ID);

            verify(exercisesRepository).delete(exercise);
        }

        /**
         * [왜] exercise_sessions 의 FK 에는 ON DELETE CASCADE 가 없다(V1__baseline.sql:110).
         * 가드가 없으면 DB 가 제약 위반을 던지고, 그건 핸들러에서 500 이 된다 — 409 여야 하는 것이.
         */
        @Test
        @DisplayName("세션 이력이 있으면 W011 로 거부하고 삭제를 시도하지 않는다")
        void delete_withSessions_rejects() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise()));
            when(sessionRepository.existsByExerciseId(EXERCISE_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.deleteExercise(EXERCISE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_DELETE_NOT_ALLOWED);

            verify(exercisesRepository, never()).delete(any());
        }

        /**
         * [왜] exists 검사와 delete 사이에 세션이 생기는 레이스가 남아 있다. 그때 DB 가 던지는
         * DataIntegrityViolationException 을 안 받으면 GlobalExceptionHandler 의 Exception 핸들러로
         * 떨어져 <b>409 대신 500</b> 이 나간다. 두 경로의 답이 같아야 한다는 것을 고정한다.
         */
        @Test
        @DisplayName("검사 후 참조가 생겨 제약 위반이 나도 500 이 아니라 같은 W011")
        void delete_raceAfterCheck_stillW011() {
            Exercise exercise = exercise();
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
            when(sessionRepository.existsByExerciseId(EXERCISE_ID)).thenReturn(false);
            doThrow(new DataIntegrityViolationException("FK constraint"))
                    .when(exercisesRepository).flush();

            assertThatThrownBy(() -> service.deleteExercise(EXERCISE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_DELETE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
        void delete_notFound_throws() {
            when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteExercise(EXERCISE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
        }
    }
}