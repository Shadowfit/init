package com.shadowfit.service.exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.AdminExerciseDetailDto;
import com.shadowfit.dto.admin.AdminExerciseListItemDto;
import com.shadowfit.dto.admin.AdminExerciseSearchCondition;
import com.shadowfit.dto.admin.AdminExerciseSortKey;
import com.shadowfit.dto.admin.ExerciseCreateDto;
import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ExerciseUpdateDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExerciseQueryRepository;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminExerciseService {

    /** 페이지 크기 상한. 없으면 size=1000000 한 방으로 전체를 긁어갈 수 있다 (AdminMemberService 와 동일). */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final ExercisesRepository exercisesRepository;
    private final ExerciseQueryRepository exerciseQueryRepository;
    private final SessionRepository sessionRepository;
    private final ExerciseReferenceRepository exerciseReferenceRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    // ─── 조회 ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminExerciseListItemDto> searchExercises(
            AdminExerciseSearchCondition condition,
            AdminExerciseSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = normalizeSize(size);
        return exerciseQueryRepository.searchForAdmin(condition, sortKey, ascending, safePage, safeSize);
    }

    /**
     * 운동 상세.
     *
     * <p>캐시된 {@code findByIdCached} 가 아니라 {@code findById} 를 쓴다. 관리자 상세는 방금
     * 수정한 값을 확인하는 자리라 최대 1시간 낡은 값을 보여주면 "저장이 안 됐다"로 읽힌다.
     */
    @Transactional(readOnly = true)
    public AdminExerciseDetailDto getExercise(Long exerciseId) {
        return AdminExerciseDetailDto.fromEntity(findOrThrow(exerciseId));
    }

    // ─── 등록 ──────────────────────────────────────────────────────────────────────

    /**
     * 운동 종목 등록.
     *
     * <p><b>{@code analysisSupported} 는 서버가 {@code false} 로 고정한다</b> — 엔티티 기본값을
     * 그대로 쓰고 DTO 에 받는 자리를 두지 않는다({@link ExerciseCreateDto} 주석). 임계값 4종도
     * 같은 이유로 기본값(60/85/70/50)에서 시작한다.
     *
     * <p>캐시 무효화가 없는 것은 의도다 — 새 id 는 {@code exercises} 캐시에 있을 수 없다.
     */
    @Transactional
    public AdminExerciseDetailDto createExercise(ExerciseCreateDto dto) {
        validateJsonOrThrow(dto.targetJoints());
        rejectDuplicateCode(dto.code(), null);

        Category category = findCategoryOrThrow(dto.categoryId());

        Exercise exercise = Exercise.builder()
                .name(dto.name())
                .code(dto.code())
                .category(category)
                .description(dto.description())
                .preferredUrl(dto.preferredUrl())
                .targetJoints(dto.targetJoints())
                .build();

        // 생략되면 엔티티의 @Builder.Default(15)가 그대로 남는다. 빌더에 null 을 넘기면 기본값을
        // 덮어써 NOT NULL 위반이 되므로, 값이 있을 때만 설정한다.
        exercise.applyExpectedDuration(dto.expectedDurationMinutes());

        Exercise saved = flushOrCodeDuplication(() -> exercisesRepository.saveAndFlush(exercise), dto.code());
        log.info("운동 종목 등록: id={}, name={}, code={}, category={} (analysisSupported=false 고정)",
                saved.getId(), saved.getName(), saved.getCode(), category.getName());

        return AdminExerciseDetailDto.fromEntity(saved);
    }

    // ─── 수정 ──────────────────────────────────────────────────────────────────────

    /**
     * 운동 종목 수정 — 보낸 필드만 갱신한다({@link ExerciseUpdateDto} 주석).
     *
     * <p>{@code findById}(캐시 미적용) 유지 — 캐시된 {@code findByIdCached} 는 detached 엔티티를
     * 반환해 아래 setter 가 dirty-checking 에 안 잡히고 <b>조용히 무시된다</b>.
     * {@code updateThresholds} 와 같은 이유이고, 같은 이유로 {@code @CacheEvict} 도 함께 건다.
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public AdminExerciseDetailDto updateExercise(Long exerciseId, ExerciseUpdateDto dto) {
        validateJsonOrThrow(dto.targetJoints());

        Exercise exercise = findOrThrow(exerciseId);

        // name·category 는 DB NOT NULL 이라 "안 보내는 것"은 허용하고 "보낸 값이 빈 것"만 막는다.
        // 이 검증은 엔티티가 아니라 서비스 몫이다(applyUpdate 의 null 체크와 성격이 다르다).
        if (dto.name() != null && !StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // categoryId 가 안 왔으면(null) 카테고리를 안 바꾼다 — applyUpdate 의 null=유지 규약과
        // 같은 자리라, 여기서 조회를 건너뛴다(조회 자체가 categoryId 필수인 findCategoryOrThrow
        // 를 안 타야 "생략 = 유지"가 성립한다).
        Category category = dto.categoryId() == null ? null : findCategoryOrThrow(dto.categoryId());

        exercise.applyUpdate(dto.name(), category, dto.description(),
                dto.preferredUrl(), dto.targetJoints(), dto.expectedDurationMinutes());
        if (applyCodeChange(exercise, dto.code())) {
            // dirty-checking 은 커밋 때 flush 하는데, 그때 터지는 UNIQUE 위반은 이 메서드 밖이라 잡을 수 없다.
            // 코드가 바뀐 경우만 여기서 flush 해 경합을 W018 로 접는다.
            flushOrCodeDuplication(() -> { exercisesRepository.flush(); return exercise; }, dto.code());
        }

        log.info("운동 종목 수정: id={}, name={}", exerciseId, exercise.getName());
        return AdminExerciseDetailDto.fromEntity(exercise);
    }

    /**
     * 업로드된 기준 영상 경로를 행에 붙인다 — mp4 업로드 흐름의 <b>트랜잭션 구간</b>이다
     * ({@link ReferenceVideoService#upload} 가 앞뒤를 맡는다).
     *
     * <p>여기엔 파일 I/O 도 gRPC 도 없다. 커밋 뒤에 해야 할 일(AI 에 추출 요청, 이전 파일 삭제)은 호출자가
     * {@code afterCommit} 으로 넘기고, 이 메서드는 그것을 {@code TransactionSynchronization.afterCommit}
     * 에 건다 — {@code ExerciseAnalysisService.startAnalysis} 와 같은 패턴이다. 트랜잭션 안에서 발사하면
     * ① 커넥션을 쥔 채 네트워크를 기다리고 ② 롤백돼도 AI 는 이미 추출을 시작해 되돌아온 콜백이
     * 없는 경로로 정답지를 덮는다.
     *
     * <p>{@code findById}(캐시 미적용)·{@code @CacheEvict} 는 {@link #updateExercise} 와 같은 이유다.
     *
     * @param afterCommit 커밋 <b>뒤에</b> 이전 경로(없으면 null)를 받아 실행된다. 롤백되면 안 불린다
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public AdminExerciseDetailDto attachReferenceVideo(Long exerciseId, String relativePath,
                                                       Consumer<String> afterCommit) {
        Exercise exercise = findOrThrow(exerciseId);
        String previous = exercise.attachReferenceVideo(relativePath);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommit.accept(previous);
            }
        });

        log.info("기준 영상 경로 갱신: exerciseId={}, {} -> {}", exerciseId, previous, relativePath);
        return AdminExerciseDetailDto.fromEntity(exercise);
    }

    // findById(캐시 미적용) 유지 — 캐시된 findByIdCached는 detached 엔티티를 반환해
    // 아래 setter가 dirty-checking에 안 잡히고 조용히 무시됨. evict만 캐시에 반영.
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public ExerciseThresholdResponseDto updateThresholds(Long exerciseId, ThresholdUpdateDto dto) {
        // beginner < advanced만 검증. diet/rehab은 숙련도 축이 아니라 목적(체중감량/안전)이 달라
        // beginner·advanced와 순서 관계를 강제할 이유가 없음 — 개별 범위(0~100)만 DTO에서 검증.
        if (dto.beginner().compareTo(dto.advanced()) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Exercise exercise = exercisesRepository.findById(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        log.info("운동 {} 임계값 변경: beginner {} -> {}, advanced {} -> {}, diet {} -> {}, rehab {} -> {}",
                exerciseId,
                exercise.getSyncThresholdBeginner(), dto.beginner(),
                exercise.getSyncThresholdAdvanced(), dto.advanced(),
                exercise.getSyncThresholdDiet(), dto.diet(),
                exercise.getSyncThresholdRehab(), dto.rehab());

        exercise.updateThresholds(dto.beginner(), dto.advanced(), dto.diet(), dto.rehab());

        return ExerciseThresholdResponseDto.fromEntity(exercise);
    }

    // ─── 분석 활성화 ────────────────────────────────────────────────────────────────

    /**
     * AI 분석 활성화 여부 변경 — {@code exercises.analysis_supported}.
     *
     * <p>이 값이 {@code true} 가 되면 {@code SessionService.createSession} 의 W007 가드가 열려
     * <b>세션이 실제로 시작된다</b>. 그래서 등록·수정 DTO 에서 빼고 전용 경로로 분리했다
     * ({@link com.shadowfit.dto.admin.AnalysisSupportUpdateDto} 주석).
     *
     * <p><b>@CacheEvict 가 여기서는 선택이 아니다.</b> 이 플래그를 읽는 것이 바로
     * {@code SessionService.createSession} 의 {@code findByIdCached}
     * ({@code SessionService.java:107})다 — Caffeine {@code expireAfterWrite=1h} 라 evict 가
     * 없으면 <b>켜도 최대 1시간 동안 W007 로 계속 막히고, 꺼도 1시간 동안 계속 열린다.</b>
     * 뒤쪽이 특히 나쁘다.
     *
     * <h4>켤 때만 가드를 건다 — 필요조건 둘</h4>
     * <ol>
     *   <li>종목 코드({@code exercises.code})가 없으면 거부한다(W020). 코드는 ai-server 가 분석기를 고르는
     *       키라, 없으면 {@code StartAnalysis} 가 거절돼 세션마다 FAILED 가 된다(#147 ㄷ, {@code analyzer_registry.py}).</li>
     *   <li>기준 좌표({@code exercise_references})가 0건이면 거부한다(W012). 그것이 분석의 실제
     *       입력이고, 비어 있으면 ai-server 가 <b>경고만 하고 진행해</b> {@code sync_rate} 가 전부 0 이
     *       되기 때문이다({@code exercise_servicer.py} StartAnalysis 의 reference_angles 경고).</li>
     * </ol>
     * 끄는 방향은 안전하므로 가드가 없다.
     *
     * <h4>⚠️ 둘 다 필요조건이지 충분조건이 아니다</h4>
     * Spring 은 ai-server 에 «이 코드의 분석기가 있는가» 를 물을 RPC 가 없다(#147 ㄴ 미채택). 그래서 코드가
     * 있어도 분석기가 없으면 켜지고, 그 종목의 세션은 {@code StartAnalysis} 거절로 FAILED 가 된다 — 예전처럼
     * «스쿼트 기준으로 조용히 틀린 점수」 가 나오지는 않는다(그 하드코딩은 #147 로 제거됐다). 켜는 순간을
     * WARN 으로 남겨 «켰는데 세션이 계속 실패」 를 추적할 단서로 둔다.
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public AdminExerciseDetailDto updateAnalysisSupport(Long exerciseId, boolean supported) {
        Exercise exercise = findOrThrow(exerciseId);

        // 켤 때의 필요조건 둘 — 코드(어느 분석기인가)와 기준 좌표(무엇과 비교하나). 코드를 먼저 보는
        // 이유는 그것이 더 앞선 질문이라서다: 코드가 없으면 기준 좌표를 뽑을 분석기도 정해지지 않는다.
        if (supported && exercise.getCode() == null) {
            log.warn("분석 활성화 거부 — 종목 코드 없음: id={}, name={}", exerciseId, exercise.getName());
            throw new BusinessException(ErrorCode.EXERCISE_CODE_REQUIRED);
        }
        if (supported && !exerciseReferenceRepository.existsByExerciseId(exerciseId)) {
            log.warn("분석 활성화 거부 — 기준 좌표 0건: id={}, name={}", exerciseId, exercise.getName());
            throw new BusinessException(ErrorCode.EXERCISE_ANALYSIS_ENABLE_BLOCKED);
        }

        boolean before = exercise.changeAnalysisSupport(supported);

        if (supported && !before) {
            // 켜는 것만 WARN 이다. Spring 은 ai-server 에 이 코드의 분석기가 있는지 물을 수 없다 —
            // 없으면 StartAnalysis 가 거절돼 세션이 FAILED 로 닫힌다(#147 ㄷ, analyzer_registry.py).
            // «켰는데 세션이 계속 실패한다» 를 추적할 때 이 줄이 단서다.
            log.warn("분석 활성화: id={}, name={}, code={} — ai-server 에 이 코드의 분석기가 없으면 "
                    + "StartAnalysis 가 거절된다(analyzer_registry.py).",
                    exerciseId, exercise.getName(), exercise.getCode());
        } else {
            log.info("분석 활성화 여부 변경: id={}, name={}, {} -> {}",
                    exerciseId, exercise.getName(), before, supported);
        }

        return AdminExerciseDetailDto.fromEntity(exercise);
    }

    // ─── 삭제 ──────────────────────────────────────────────────────────────────────

    /**
     * 운동 종목 삭제 — <b>하드 삭제</b>. 세션 이력이 있으면 거부한다(W011).
     *
     * <p>딸린 데이터의 운명이 FK 정의에 따라 갈린다({@code V1__baseline.sql}):
     * <ul>
     *   <li>{@code exercise_references}(:83)·{@code exercise_feedback_templates}(:278) —
     *       {@code ON DELETE CASCADE} 라 <b>같이 지워진다</b>. 기준 좌표와 피드백 멘트는 종목에
     *       종속된 부속물이라 종목이 사라지면 의미가 없다</li>
     *   <li>{@code exercise_sessions}(:110) — CASCADE 가 <b>없다</b>. 회원의 운동 이력이라
     *       종목을 지운다고 없애도 되는 데이터가 아니다</li>
     * </ul>
     * 즉 "이력이 있으면 못 지운다"는 이 메서드가 새로 만든 규칙이 아니라 <b>스키마가 이미 내린
     * 결정</b>이고, 아래 가드는 그걸 500 대신 409 로 옮긴다.
     *
     * <p>⚠️ <b>검사와 삭제 사이 레이스가 남는다.</b> {@code exists} 가 false 를 준 직후 누군가
     * 그 종목으로 세션을 시작하면 DELETE 가 FK 제약에 걸린다. 그걸 막는 것이 아니라 <b>받아서
     * 같은 409 로 바꾼다</b> — 잠금으로 막으려면 종목 행을 잠근 채 세션 생성 경로 전체와
     * 직렬화해야 하는데, 그건 3행짜리 마스터 테이블 삭제가 살 비용이 아니다. 결과적으로
     * 사용자에게 보이는 답은 두 경로가 같다.
     *
     * <p>{@code DataIntegrityViolationException} 을 안 받으면 {@code GlobalExceptionHandler} 의
     * {@code Exception} 핸들러로 떨어져 <b>409 대신 500</b> 이 나간다 — 그 클래스의 버그가 이
     * 코드베이스에서 이미 세 번 있었다(403→500, 400→500, 404→500. 같은 파일 주석 참고).
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public void deleteExercise(Long exerciseId) {
        Exercise exercise = findOrThrow(exerciseId);

        if (sessionRepository.existsByExerciseId(exerciseId)) {
            log.warn("운동 종목 삭제 거부 — 세션 이력 존재: id={}, name={}", exerciseId, exercise.getName());
            throw new BusinessException(ErrorCode.EXERCISE_DELETE_NOT_ALLOWED);
        }

        try {
            exercisesRepository.delete(exercise);
            // 제약 위반은 flush 시점에 터진다. 트랜잭션이 끝난 뒤 나면 이 try 를 빠져나간 뒤라
            // 여기서 잡을 수 없으므로 명시적으로 flush 해서 경계를 이 안으로 당긴다.
            exercisesRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 위 exists 검사와 여기 사이에 세션이 생긴 경우 (클래스 주석의 레이스).
            log.warn("운동 종목 삭제 실패 — 검사 후 참조가 생김: id={}", exerciseId, e);
            throw new BusinessException(ErrorCode.EXERCISE_DELETE_NOT_ALLOWED);
        }

        log.info("운동 종목 삭제: id={}, name={}", exerciseId, exercise.getName());
    }

    // ─── 공통 ──────────────────────────────────────────────────────────────────────

    // ─── 종목 코드 ──────────────────────────────────────────────────────────────────

    /**
     * 코드 중복 검사 — UNIQUE(uk_exercises_code) 가 어차피 막지만 500 대신 W018 로 이유를 준다
     * ({@code AdminCategoryService} 의 이름 중복과 같은 결). null 코드는 «분석기 없음» 이라 검사 대상이 아니다.
     *
     * <p>검사와 저장 사이의 레이스는 잠금으로 막지 않는다(삭제 경로의 판단과 같다) — 그때 UNIQUE 가 던지는
     * 위반은 {@link #flushOrCodeDuplication} 이 같은 W018 로 접는다. 사전 검사를 그래도 두는 이유는 정상 경로에서
     * INSERT 를 시도조차 안 하고 이유를 주기 위해서다.
     *
     * @param selfId 수정 중인 행의 id — 자기 코드를 다시 보내는 것은 중복이 아니다. 등록이면 null
     */
    private void rejectDuplicateCode(String code, Long selfId) {
        if (code == null) return;
        boolean taken = selfId == null
                ? exercisesRepository.existsByCode(code)
                : exercisesRepository.existsByCodeAndIdNot(code, selfId);
        if (taken) {
            log.warn("종목 코드 중복: code={}, selfId={}", code, selfId);
            throw new BusinessException(ErrorCode.EXERCISE_CODE_DUPLICATION);
        }
    }

    /**
     * 수정(PATCH) 의 코드 변경 — null 이면 «안 바꿈»({@link ExerciseUpdateDto} 규약), 같은 값이면 no-op.
     *
     * <p><b>분석이 켜진 종목은 거부한다(W019).</b> 코드는 ai-server 가 분석기를 고르는 키라, 켜진 채 바꾸면
     * 그 순간부터 시작되는 세션이 다른 분석기 기준으로 채점된다 — 그리고 «기준 좌표는 옛 종목 것» 인 상태다.
     * 끄고(세션 시작 차단) → 바꾸고 → 기준 영상을 다시 올려 → 켜는 순서를 강제한다.
     */
    private boolean applyCodeChange(Exercise exercise, String newCode) {
        if (newCode == null || newCode.equals(exercise.getCode())) return false;
        if (Boolean.TRUE.equals(exercise.getAnalysisSupported())) {
            log.warn("종목 코드 변경 거부 — 분석 활성 상태: id={}, {} -> {}", exercise.getId(), exercise.getCode(), newCode);
            throw new BusinessException(ErrorCode.EXERCISE_CODE_LOCKED);
        }
        rejectDuplicateCode(newCode, exercise.getId());
        log.info("종목 코드 변경: id={}, {} -> {}", exercise.getId(), exercise.getCode(), newCode);
        exercise.changeCode(newCode);
        return true;
    }

    /**
     * 저장/flush 를 실행하고, {@code uk_exercises_code} 위반만 W018 로 바꾼다 — 사전 검사와 INSERT 사이에 같은
     * 코드가 먼저 들어온 경합. 그 외 무결성 위반(FK·NOT NULL·JSON)은 클라 잘못이 아니라 서버 결함이 대부분이라
     * 그대로 던진다({@code MemberService.duplicationOf} 와 같은 규칙 — 이름을 확인한 제약만 4xx 로).
     *
     * <p>flush 를 여기서 하는 이유: 위반은 SQL 이 나갈 때 터지는데, 그 시점이 커밋이면 이 메서드 밖이라 잡을 수 없다.
     */
    private <T> T flushOrCodeDuplication(java.util.function.Supplier<T> action, String code) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            String constraint = (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve)
                    ? cve.getConstraintName() : null;
            if (constraint != null && constraint.toLowerCase(java.util.Locale.ROOT).contains("uk_exercises_code")) {
                log.warn("종목 코드 경합 — 사전검사 후 선점됨: code={} (constraint={})", code, constraint);
                throw new BusinessException(ErrorCode.EXERCISE_CODE_DUPLICATION);
            }
            throw e;
        }
    }

    private Exercise findOrThrow(Long exerciseId) {
        return exercisesRepository.findById(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));
    }

    private Category findCategoryOrThrow(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    /**
     * {@code target_joints} 는 MySQL {@code json} 컬럼이라 형식이 깨진 문자열은 DB 가 거부한다
     * (에러 3140). 그대로 두면 {@code DataIntegrityViolationException} → <b>500</b> 이 나가므로,
     * 저장 전에 걸러 400 으로 답한다.
     *
     * <p>null 은 통과시킨다 — 컬럼이 nullable 이고, PATCH 에서 null 은 "안 바꿈"이다.
     */
    private void validateJsonOrThrow(String json) {
        if (json == null) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("targetJoints 가 유효한 JSON 이 아님: {}", e.getOriginalMessage());
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}