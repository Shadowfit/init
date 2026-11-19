package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercises")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString // BE-04 — category 는 연관관계라 exclude(LAZY 를 트랜잭션 밖에서 찍으면 예외)
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * AI 분석기 키({@code SQUAT}·{@code LUNGE}·…). <b>NULL = 분석기가 없는 종목</b>이라 관리자가 만든
     * 종목은 비어 있고, {@code analysis_supported} 를 켜려면 이 값이 있어야 한다
     * ({@code AdminExerciseService.updateAnalysisSupport}). 값 규칙은 {@code ExerciseCode.PATTERN}.
     *
     * <p>이 컬럼이 생긴 이유는 V25 머리 주석 — DB id 를 ai-server·프론트·시드가 따로 하드코딩하던 것을
     * 한 값으로 모은다. 분석이 켜진 종목의 코드를 바꾸면 진행 중 세션이 다른 분석기로 넘어가므로
     * {@link #changeCode} 가 아니라 서비스가 그 조건을 막는다.
     */
    @Column(length = 32, unique = true)
    private String code;

    // BE-04 — 카테고리가 고정 enum 에서 관리 가능한 테이블로 승격됐다(V11 마이그레이션).
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "preferred_url", length = 500)
    private String preferredUrl;

    /**
     * 기준 좌표를 뽑은 업로드 영상 — 저장 루트({@code reference-video.dir}) 기준 <b>상대 경로</b>
     * ({@code {id}/{uuid}.mp4}). NULL 이면 업로드로 등록된 영상이 없다(V4 시드 스쿼트가 그렇다).
     * 절대 경로를 안 박는 이유는 V23 주석 — Spring 이 쓰는 경로와 AI 가 읽는 경로가 문자열로는 다를 수 있다.
     */
    @Column(name = "reference_video_path", length = 500)
    private String referenceVideoPath;

    /**
     * SQL의 JSON 타입을 매핑합니다.
     * 가장 간단하게는 String으로 처리한 뒤,
     * 필요할 때 Jackson ObjectMapper로 파싱하여 사용합니다.
     */
    @Column(columnDefinition = "json")
    private String targetJoints;

    /**
     * 싱크로율 판정 기준 한 쌍(V34, 2026-10-21 «하나로 통일»). 점수 ≥ good = 정석, ≥ warn = 교정 필요,
     * 그 아래 = 위험. 페르소나와 무관하다(페르소나는 트레이너 말투로만 남는다).
     *
     * <p>예전 페르소나별 4컬럼(beginner/advanced/diet/rehab)은 관리자 API 로 바꿀 수만 있고 읽는 곳이 없었다.
     * 이 한 쌍은 AI({@code AnalyzeRequest}·{@code ReattachRequest})와 앱(세션 시작·재부착·진행 중 세션·리포트
     * 응답)으로 같이 흐른다 — {@code ExerciseAnalysisService.SessionTargets} 가 그 변환 한 곳이다.
     */
    @Builder.Default
    @Column(name = "sync_good_threshold", nullable = false, precision = 5, scale = 2)
    private BigDecimal syncGoodThreshold = new BigDecimal("80.00");

    @Builder.Default
    @Column(name = "sync_warn_threshold", nullable = false, precision = 5, scale = 2)
    private BigDecimal syncWarnThreshold = new BigDecimal("60.00");

    /**
     * 기준 좌표 등록의 가장 최근 시도 결과(V34). NULL = 등록 시도가 없었다 — 관리자 화면엔 NONE.
     * 영상 업로드는 EXTRACTING 으로 시작해 AI 역호출(또는 접수 실패)이 READY/FAILED 로 닫는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_extraction_status", length = 16)
    private ReferenceExtractionStatus referenceExtractionStatus;

    /** FAILED 사유 — 관리자에게 그대로 보여준다. 다른 상태에선 null. */
    @Column(name = "reference_extraction_error", length = 500)
    private String referenceExtractionError;

    /** 위 상태가 바뀐 시각. EXTRACTING 이 오래 그대로면 조회 쪽이 FAILED 로 보여주는 기준이다. */
    @Column(name = "reference_extraction_at")
    private LocalDateTime referenceExtractionAt;

    @Builder.Default
    @Column(nullable = false)
    private Integer expectedDurationMinutes = 15; // 예상 운동시간 (기본값: 15분)

    /**
     * AI 서버가 이 종목의 자세 분석을 실제로 지원하는지. 기본값 false — 종목 행이 먼저 생기고
     * 분석기(ai-server)가 나중에 붙는 순서라, 기본을 true로 두면 준비 전에 세션이 열린다.
     * 현재 true인 건 스쿼트뿐(squat-first 방침). SessionService.createSession 이 이 값을 검사한다.
     *
     * <p><b>캐시 주의</b>: 이 엔티티는 {@code ExercisesRepository.findByIdCached} 를 통해 Caffeine에
     * 캐시된다(expireAfterWrite=1h). 값을 SQL로 직접 바꾸면 최대 1시간 동안 반영되지 않으므로 즉시
     * 반영하려면 애플리케이션 재시작 또는 캐시 비우기가 필요하다. 향후 이 플래그를 바꾸는 관리자 API를
     * 만든다면 {@code AdminExerciseService.updateThresholds} 처럼
     * {@code @CacheEvict(cacheNames = "exercises", key = "#exerciseId")} 를 함께 걸어야 한다.
     */
    @Builder.Default
    @Column(name = "analysis_supported", nullable = false)
    private Boolean analysisSupported = false;

    /** 기준 자세 채점 없이 반복 횟수만 측정하는 종목. */
    @Builder.Default
    @Column(name = "repetition_only", nullable = false)
    private Boolean repetitionOnly = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ─── 전이 ────────────────────────────────────────────────────────────────
    // 이슈 #174 — setter 를 전면 개방하는 대신 "무엇을 바꾸는가"에 이름을 준다.

    /** 등록 시 예상 시간만 조건부로 덮어쓴다 — null 이면 {@code @Builder.Default}(15분)가 그대로 남는다. */
    public void applyExpectedDuration(Integer minutes) {
        if (minutes != null) this.expectedDurationMinutes = minutes;
    }

    /** 관리자 수정(PATCH) — 보낸 필드만 갱신한다. null 검증(공백 등)은 호출자(서비스) 몫이다. */
    public void applyUpdate(String name, Category category, String description,
                             String preferredUrl, String targetJoints, Integer expectedDurationMinutes) {
        if (name != null) this.name = name;
        if (category != null) this.category = category;
        if (description != null) this.description = description;
        if (preferredUrl != null) this.preferredUrl = preferredUrl;
        if (targetJoints != null) this.targetJoints = targetJoints;
        if (expectedDurationMinutes != null) this.expectedDurationMinutes = expectedDurationMinutes;
    }

    /**
     * 종목 코드를 바꾼다. «분석이 켜진 종목은 못 바꾼다» 는 호출자({@code AdminExerciseService}) 몫이다 —
     * 그 판단에 {@code analysisSupported} 와 로그가 같이 필요해 여기서 접지 않는다.
     */
    public void changeCode(String code) {
        this.code = code;
    }

    /**
     * 업로드된 기준 영상 경로를 바꾼다.
     *
     * @return 바뀌기 <b>전</b> 경로(없으면 null) — 호출자가 커밋 뒤에 이전 파일을 지워야 해서 원값을 준다.
     *         {@link #changeAnalysisSupport} 와 같은 이유로 판정을 미리 접지 않는다.
     */
    public String attachReferenceVideo(String relativePath) {
        String before = this.referenceVideoPath;
        this.referenceVideoPath = relativePath;
        return before;
    }

    /**
     * 싱크로율 판정 기준 한 쌍을 바꾼다. {@code 0 < warn < good ≤ 100} 검증은 호출자(서비스)가 이미 했다.
     * 컬럼이 DECIMAL(5,2) 라 소수 둘째 자리에서 반올림해 둔다 — DB 가 조용히 자르는 값과 응답 값이 같게.
     */
    public void updateThresholds(BigDecimal good, BigDecimal warn) {
        this.syncGoodThreshold = good.setScale(2, java.math.RoundingMode.HALF_UP);
        this.syncWarnThreshold = warn.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ─── 기준 좌표 등록 상태 (V34) ─────────────────────────────────────────────

    /** {@code reference_extraction_error} 컬럼 길이 — 넘는 사유는 잘라서 저장한다. */
    public static final int REFERENCE_EXTRACTION_ERROR_MAX = 500;

    /** 영상 업로드가 AI 추출을 요청했다 — 결과는 역호출이 READY/FAILED 로 닫는다. */
    public void markReferenceExtracting() {
        this.referenceExtractionStatus = ReferenceExtractionStatus.EXTRACTING;
        this.referenceExtractionError = null;
        this.referenceExtractionAt = LocalDateTime.now();
    }

    /** 기준 좌표가 교체됐다(영상 추출 완료 또는 3D 시범 직접 등록). */
    public void markReferenceReady() {
        this.referenceExtractionStatus = ReferenceExtractionStatus.READY;
        this.referenceExtractionError = null;
        this.referenceExtractionAt = LocalDateTime.now();
    }

    /** 추출이 실패했다. 기존 기준 좌표는 건드리지 않는 것이 호출자 몫이다. 사유는 500자에서 자른다. */
    public void markReferenceFailed(String reason) {
        String r = reason == null || reason.isBlank() ? "알 수 없는 이유로 추출에 실패했어요." : reason.strip();
        this.referenceExtractionStatus = ReferenceExtractionStatus.FAILED;
        this.referenceExtractionError = r.length() > REFERENCE_EXTRACTION_ERROR_MAX
                ? r.substring(0, REFERENCE_EXTRACTION_ERROR_MAX) : r;
        this.referenceExtractionAt = LocalDateTime.now();
    }

    /**
     * 분석 지원 여부를 바꾼다.
     *
     * @return 바뀌기 <b>전</b> 값 — 호출자가 "꺼져 있다가 켜졌는지"(WARN 로그 분기)와
     *         "이전 -> 이후"(INFO 로그) 를 둘 다 구성해야 해서, 판정을 미리 접지 않고 원값을 준다.
     */
    public boolean changeAnalysisSupport(boolean supported) {
        boolean before = Boolean.TRUE.equals(this.analysisSupported);
        this.analysisSupported = supported;
        return before;
    }
}
