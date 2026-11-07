package com.shadowfit.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.report.Report;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.report.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReportService 단위 테스트 — precompute-on-write 도입(2026-07-24) 후 읽기 경로의 핵심 분기:
 * detailed_analysis 존재 시 재계산 없이 읽기만 하는지(§9 목적), 없을 때만 pose_data로 하위호환
 * fallback하는지(§9-4, 백필 없이 안전).
 */
@DisplayName("ReportService 테스트")
class ReportServiceTest {

    @Mock private ReportRepository reportRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PoseDataRepository poseDataRepository;
    @Mock private SessionAnalysisCalculator sessionAnalysisCalculator;
    @Mock private SquatDepthAnalysisCalculator squatDepthAnalysisCalculator;
    @Mock private LungeDepthAnalysisCalculator lungeDepthAnalysisCalculator;
    @Mock private SquatTrunkLeanAnalysisCalculator squatTrunkLeanAnalysisCalculator;
    @Mock private LungeTrunkLeanAnalysisCalculator lungeTrunkLeanAnalysisCalculator;
    @Mock private LungeStepLengthAnalysisCalculator lungeStepLengthAnalysisCalculator;
    @Mock private com.shadowfit.repository.exercise.SessionSetRepository sessionSetRepository;
    @Mock private com.shadowfit.repository.exercise.RejectedAttemptRepository rejectedAttemptRepository;

    private ReportService reportService;

    private Session session;
    private Report report;
    private static final Long MEMBER_ID = 1L;
    private static final Long SESSION_ID = 10L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 실제 JSON 직렬화/역직렬화 동작이 검증 대상이라 ObjectMapper는 모킹하지 않고 실사용
        reportService = new ReportService(reportRepository, sessionRepository, poseDataRepository,
                sessionAnalysisCalculator, squatDepthAnalysisCalculator, lungeDepthAnalysisCalculator,
                squatTrunkLeanAnalysisCalculator, lungeTrunkLeanAnalysisCalculator,
                lungeStepLengthAnalysisCalculator, new ObjectMapper(), sessionSetRepository, rejectedAttemptRepository);

        Member member = Member.builder().id(MEMBER_ID).email("t@t.com").username("u").password("p").build();
        Category category = Category.builder().id(1L).name("LOWER").build();
        Exercise exercise = Exercise.builder().id(1L).name("스쿼트").category(category)
                .expectedDurationMinutes(15)
                .build();
        session = Session.builder()
                .id(SESSION_ID).member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(20))
                .endTime(LocalDateTime.now())
                .status(Status.COMPLETED)
                .totalReps(10)
                .avgSyncRate(new BigDecimal("77.5"))
                .caloriesBurned(new BigDecimal("50.0"))
                .build();

        report = new Report();
        report.setMember(member);
        report.setSession(session);

        when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
                anyLong(), anyLong(), any(Status.class), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("세션이 없으면(본인 것 아니거나 존재 안 함) SESSION_NOT_FOUND")
    void sessionNotFound() {
        when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getSessionReport(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(reportRepository, never()).findBySessionId(anyLong());
    }

    @Test
    @DisplayName("리포트가 없으면 REPORT_NOT_FOUND")
    void reportNotFound() {
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getSessionReport(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("precompute된 detailed_analysis가 있으면 그 값을 읽기만 하고 pose_data는 재조회 안 함")
    void precomputedAnalysis_skipsPoseDataRecompute() throws Exception {
        WorstSectionDto precomputed = new WorstSectionDto();
        precomputed.setRepNumber(2);
        precomputed.setExerciseName("스쿼트");
        precomputed.setTimeStamp("01:15");
        precomputed.setReason("2회차 · 싱크로율 75%");
        SessionDetailedAnalysis stored = new SessionDetailedAnalysis(precomputed, List.of(
                new RepSyncRateDto(1, 80.0, "00:12"),
                new RepSyncRateDto(2, 75.0, "01:15")
        ), null, null, null, null, null);
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(stored));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getWorstSection()).isNotNull();
        assertThat(result.getWorstSection().getRepNumber()).isEqualTo(2);
        assertThat(result.getRepTrend())
                .extracting(RepSyncRateDto::getRepNumber).containsExactly(1, 2);
        // 추이를 응답에 넣으면서 pose_data 스캔이 되살아나지 않았는지가 이 테스트의 핵심이다
        verify(poseDataRepository, never()).findFramesBySessionId(anyLong(), any());
        verify(sessionAnalysisCalculator, never()).calculate(any(), any());
        verify(sessionAnalysisCalculator, never()).calculateRepTrend(any(), any());
        verify(rejectedAttemptRepository, never()).findSummariesBySessionId(anyLong());
    }

    @Test
    @DisplayName("리포트에 세션 종목의 판정 기준(syncGoodThreshold·syncWarnThreshold)이 실린다 — V34")
    void report_carriesExerciseThresholds() throws Exception {
        Exercise custom = Exercise.builder().id(2L).name("런지").category(session.getExercise().getCategory())
                .expectedDurationMinutes(15)
                .syncGoodThreshold(new BigDecimal("85.00")).syncWarnThreshold(new BigDecimal("55.50"))
                .build();
        Session customSession = Session.builder()
                .id(SESSION_ID).member(session.getMember()).exercise(custom)
                .startTime(LocalDateTime.now().minusMinutes(20)).endTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(3).avgSyncRate(new BigDecimal("70.0"))
                .build();
        when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                .thenReturn(Optional.of(customSession));
        SessionDetailedAnalysis stored = new SessionDetailedAnalysis(null,
                List.of(new RepSyncRateDto(1, 70.0, "00:05")), null, null, null, null, null);
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(stored));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getSyncGoodThreshold()).isEqualTo(85.0);
        assertThat(result.getSyncWarnThreshold()).isEqualTo(55.5);
    }

    @Test
    @DisplayName("구버전 형식(WorstSectionDto 단독)으로 저장된 detailed_analysis는 재계산으로 흘린다")
    void legacyAnalysisFormat_fallsBackToRecompute() throws Exception {
        // 예전에는 이 컬럼에 WorstSectionDto 를 그대로 직렬화했다. 그 행을 SessionDetailedAnalysis 로
        // 읽으면 파싱이 실패하거나(엄격 모드) 전부 null 인 객체가 나온다(관대 모드). 둘 다 재계산이어야
        // 한다 — 후자만 놓치면 나중에 FAIL_ON_UNKNOWN_PROPERTIES 를 끄는 순간 worst 가 조용히 사라진다.
        WorstSectionDto legacy = new WorstSectionDto();
        legacy.setExerciseName("스쿼트");
        legacy.setTimeStamp("01:15");
        legacy.setReason("싱크로율 40% · KNEE_OUT");
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(legacy));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(1L, 0.0, 50.0, 1, 100.0, null, null, null));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(frames);
        WorstSectionDto recomputed = new WorstSectionDto();
        recomputed.setReason("재계산됨");
        when(sessionAnalysisCalculator.calculate(session, frames)).thenReturn(recomputed);
        when(sessionAnalysisCalculator.calculateRepTrend(eq(frames), any()))
                .thenReturn(List.of(new RepSyncRateDto(1, 50.0, "00:00")));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getWorstSection().getReason()).isEqualTo("재계산됨");
        assertThat(result.getRepTrend()).hasSize(1);
        verify(poseDataRepository, times(1)).findFramesBySessionId(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("detailed_analysis가 비어있으면(precompute 이전 리포트) pose_data로 하위호환 재계산")
    void blankAnalysis_fallsBackToPoseDataRecompute() {
        report.setDetailedAnalysis(null); // 시드 데이터처럼 precompute 이전 상태
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(1L, 0.0, 50.0, 1, 100.0, null, null, null));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(frames);
        WorstSectionDto recomputed = new WorstSectionDto();
        recomputed.setReason("재계산됨");
        when(sessionAnalysisCalculator.calculate(session, frames)).thenReturn(recomputed);

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getWorstSection().getReason()).isEqualTo("재계산됨");
        verify(poseDataRepository, times(1)).findFramesBySessionId(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("detailed_analysis가 깨진 JSON이면 예외 대신 pose_data 재계산으로 안전하게 대체")
    void malformedAnalysis_fallsBackGracefully() {
        report.setDetailedAnalysis("{이건 JSON이 아님");
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(1L, 0.0, 50.0, 1, 100.0, null, null, null));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(frames);
        WorstSectionDto recomputed = new WorstSectionDto();
        recomputed.setReason("재계산됨");
        when(sessionAnalysisCalculator.calculate(session, frames)).thenReturn(recomputed);

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        // 파싱 실패 시 예외를 삼키고 끝나는 게 아니라, 실제로 pose_data 재계산까지 수행됐는지 검증
        // (CodeRabbit 지적 — 예전엔 calculate()가 null을 반환해도 통과하는 부실한 테스트였음)
        assertThat(result.getWorstSection().getReason()).isEqualTo("재계산됨");
        verify(poseDataRepository, times(1)).findFramesBySessionId(eq(SESSION_ID), any());
        verify(sessionAnalysisCalculator).calculate(session, frames);
    }

    @Test
    @DisplayName("이전 동일 운동 세션이 있으면 comparisonWithPrevious 채워짐")
    void previousSessionPresent_fillsComparison() {
        report.setDetailedAnalysis(null);
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(List.of());

        Session lastSession = Session.builder()
                .id(9L).member(session.getMember()).exercise(session.getExercise())
                .startTime(LocalDateTime.now().minusDays(1).minusMinutes(30))
                .endTime(LocalDateTime.now().minusDays(1))
                .status(Status.COMPLETED)
                .avgSyncRate(new BigDecimal("70.0"))
                .caloriesBurned(new BigDecimal("40.0"))
                .build();
        when(sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
                MEMBER_ID, session.getExercise().getId(), Status.COMPLETED, SESSION_ID))
                .thenReturn(Optional.of(lastSession));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getComparisonWithPrevious()).isNotNull();
        assertThat(result.getComparisonWithPrevious().getSyncRateDiff()).isEqualTo(8); // 77.5 - 70.0 반올림
        assertThat(result.getComparisonWithPrevious().getCaloriesDiff()).isEqualTo(10); // 50.0 - 40.0
        assertThat(result.getCaloriesBurned()).isEqualTo(50);
    }

    // ── 칼로리 null 규약 (2026-10-21, CalorieEstimator) ─────────────────────

    private Session previousSessionWithCalories(BigDecimal calories) {
        return Session.builder()
                .id(9L).member(session.getMember()).exercise(session.getExercise())
                .startTime(LocalDateTime.now().minusDays(1).minusMinutes(30))
                .endTime(LocalDateTime.now().minusDays(1))
                .status(Status.COMPLETED)
                .avgSyncRate(new BigDecimal("70.0"))
                .caloriesBurned(calories)
                .build();
    }

    private void givenPreviousSession(Session last) {
        report.setDetailedAnalysis(null);
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(List.of());
        when(sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
                MEMBER_ID, session.getExercise().getId(), Status.COMPLETED, SESSION_ID))
                .thenReturn(Optional.of(last));
    }

    @Test
    @DisplayName("지난 세션 칼로리가 null(체중 모름)이면 caloriesDiff 는 0 이 아니라 null")
    void previousCaloriesNull_diffIsNull() {
        givenPreviousSession(previousSessionWithCalories(null));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getComparisonWithPrevious().getCaloriesDiff()).isNull();
        assertThat(result.getComparisonWithPrevious().getSyncRateDiff()).isEqualTo(8); // 다른 차이는 그대로
    }

    @Test
    @DisplayName("이번 세션 칼로리가 null 이면 caloriesBurned·caloriesDiff 둘 다 0 이 아니라 null — JSON 에도 null 로 실린다")
    void currentCaloriesNull_jsonCarriesNulls() throws Exception {
        Session noCalories = Session.builder()
                .id(SESSION_ID).member(session.getMember()).exercise(session.getExercise())
                .startTime(LocalDateTime.now().minusMinutes(20)).endTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(10).avgSyncRate(new BigDecimal("77.5"))
                .build();
        when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                .thenReturn(Optional.of(noCalories));
        givenPreviousSession(previousSessionWithCalories(new BigDecimal("40.0")));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getCaloriesBurned()).isNull();
        assertThat(result.getComparisonWithPrevious().getCaloriesDiff()).isNull();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("\"caloriesBurned\":null", "\"caloriesDiff\":null");
    }

    @Test
    @DisplayName("caloriesBurned 는 kcal 정수 반올림 — 10.11 → 10, 10.50 → 11 (caloriesDiff 의 반올림과 맞춘다)")
    void caloriesBurned_roundedHalfUp() {
        report.setDetailedAnalysis(null);
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(List.of());
        for (String[] c : new String[][]{{"10.11", "10"}, {"10.50", "11"}}) {
            Session s = Session.builder()
                    .id(SESSION_ID).member(session.getMember()).exercise(session.getExercise())
                    .startTime(LocalDateTime.now().minusMinutes(3)).endTime(LocalDateTime.now())
                    .status(Status.COMPLETED).totalReps(5).caloriesBurned(new BigDecimal(c[0]))
                    .build();
            when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                    .thenReturn(Optional.of(s));

            assertThat(reportService.getSessionReport(SESSION_ID, MEMBER_ID).getCaloriesBurned())
                    .isEqualTo(Integer.parseInt(c[1]));
        }
    }

    // ── 스쿼트 깊이 targetKneeDeg (2026-10-21) — 저장된 리포트의 하위호환 ────────────

    @Test
    @DisplayName("targetKneeDeg 가 생기기 전에 저장된 리포트는 그대로 읽힌다 — 재계산 없이 targetKneeDeg 만 null")
    void legacyStoredSquatDepth_readsAsIs_withNullTarget() throws Exception {
        // 옛 «100도 이하 만점» 규칙으로 precompute 돼 저장된 모양 그대로 (targetKneeDeg 필드 없음)
        report.setDetailedAnalysis("{\"worstSection\":null,"
                + "\"repTrend\":[{\"repNumber\":1,\"syncRate\":63.5,\"timeStamp\":\"00:05\"}],"
                + "\"squatDepthAnalysis\":{\"depthMode\":\"FULL\",\"depthScore\":100,\"depthGrade\":\"EXCELLENT\","
                + "\"repDepths\":[{\"repNumber\":1,\"minKneeAngle\":85.0,\"depthScore\":100,\"timeStamp\":\"00:05\"}],"
                + "\"narrative\":[\"1회 모두 목표 깊이를 안정적으로 지켰어요.\"]},"
                + "\"lungeDepthAnalysis\":null,\"squatTrunkLeanAnalysis\":null,"
                + "\"lungeTrunkLeanAnalysis\":null,\"lungeStepLengthAnalysis\":null}");
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getSquatDepthAnalysis().getDepthScore()).isEqualTo(100); // 저장된 점수 그대로(박제)
        assertThat(result.getSquatDepthAnalysis().getTargetKneeDeg()).isNull();
        verify(squatDepthAnalysisCalculator, never()).calculate(any(), any());
        verify(poseDataRepository, never()).findFramesBySessionId(anyLong(), any());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("\"targetKneeDeg\":null");
    }

    @Test
    @DisplayName("새로 저장된 리포트는 targetKneeDeg 를 싣고 응답 JSON 에 squatDepthAnalysis.targetKneeDeg 로 나간다")
    void storedSquatDepth_withTarget_roundTripsToJson() throws Exception {
        com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto depth =
                new com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto();
        depth.setDepthMode("FULL");
        depth.setDepthScore(64);
        depth.setDepthGrade("FAIR");
        depth.setRepDepths(List.of(new com.shadowfit.dto.report.detailreport.RepDepthDto(1, 85.3, 64, "00:05")));
        depth.setNarrative(List.of("1회차는 목표 깊이보다 얕았어요."));
        depth.setTargetKneeDeg(76.2);
        SessionDetailedAnalysis stored = new SessionDetailedAnalysis(null,
                List.of(new RepSyncRateDto(1, 63.5, "00:05")), depth, null, null, null, null);
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(stored));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getSquatDepthAnalysis().getTargetKneeDeg()).isEqualTo(76.2);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("\"targetKneeDeg\":76.2", "\"depthScore\":64");
    }

    // ── 미인정 시도 (V35, 2026-10-21) ────────────────────────────────────

    /**
     * 미인정 시도 기록 이전에 저장된 JSON 을 문자 그대로 넣는다(새 DTO 로 직렬화하면 새 필드가 섞여 «옛 행» 이 아니다).
     * 없는 필드는 0·빈 값으로, 옛 점은 전부 인정 반복({@code counted=true})으로 읽혀야 한다.
     */
    @Test
    @DisplayName("미인정 기록 이전에 저장된 리포트 — rejectedReps 0·빈 맵, 옛 점은 counted=true·attemptNumber null")
    void legacyStoredReport_readsWithoutRejectedFields() throws Exception {
        report.setDetailedAnalysis("{\"worstSection\":null,"
                + "\"repTrend\":[{\"repNumber\":1,\"syncRate\":80.0,\"timeStamp\":\"00:05\"},"
                + "{\"repNumber\":2,\"syncRate\":70.0,\"timeStamp\":\"00:11\"}],"
                + "\"squatDepthAnalysis\":{\"depthMode\":\"FULL\",\"depthScore\":64,\"depthGrade\":\"FAIR\","
                + "\"repDepths\":[{\"repNumber\":1,\"minKneeAngle\":85.3,\"depthScore\":64,\"timeStamp\":\"00:05\"}],"
                + "\"narrative\":[\"1회차는 목표 깊이보다 얕았어요.\"],\"targetKneeDeg\":76.2},"
                + "\"lungeDepthAnalysis\":null,"
                + "\"squatTrunkLeanAnalysis\":{\"tiltScore\":100,\"tiltGrade\":\"EXCELLENT\","
                + "\"repTrunkLeans\":[{\"repNumber\":1,\"avgTiltDeg\":30.0,\"tiltScore\":100,\"timeStamp\":\"00:04\"}],"
                + "\"narrative\":[\"1회 모두 상체 자세를 잘 유지했어요.\"]},"
                + "\"lungeTrunkLeanAnalysis\":null,\"lungeStepLengthAnalysis\":null}");
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getRejectedReps()).isZero();
        assertThat(result.getRejectedRepsByReason()).isEmpty();
        assertThat(result.getRepTrend()).extracting(RepSyncRateDto::getRepNumber).containsExactly(1, 2);
        assertThat(result.getRepTrend()).allSatisfy(p -> {
            assertThat(p.isCounted()).isTrue();
            assertThat(p.getAttemptNumber()).isNull();
            assertThat(p.getRejectReason()).isNull();
            assertThat(p.getTimestampSec()).isNull();
        });
        assertThat(result.getSquatDepthAnalysis().getRepDepths().get(0).isCounted()).isTrue();
        assertThat(result.getSquatDepthAnalysis().getRepDepths().get(0).getAttemptNumber()).isNull();
        assertThat(result.getSquatTrunkLeanAnalysis().getRepTrunkLeans().get(0).isCounted()).isTrue();
        // 저장된 것을 읽기만 한다 — 옛 리포트라고 재계산하지 않는다
        verify(poseDataRepository, never()).findFramesBySessionId(anyLong(), any());
        verify(rejectedAttemptRepository, never()).findSummariesBySessionId(anyLong());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("\"rejectedReps\":0", "\"rejectedRepsByReason\":{}");
    }

    @Test
    @DisplayName("미인정 시도가 담긴 저장 리포트 — 응답에 rejectedReps·rejectedRepsByReason·counted=false 점이 그대로 나간다")
    void storedReportWithRejected_roundTripsToJson() throws Exception {
        com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto depth =
                new com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto();
        depth.setDepthMode("FULL");
        depth.setDepthScore(50);
        depth.setDepthGrade("NEEDS_WORK");
        depth.setRepDepths(List.of(
                new com.shadowfit.dto.report.detailreport.RepDepthDto(1, 76.0, 100, "00:05"),
                com.shadowfit.dto.report.detailreport.RepDepthDto.rejected(1, 101.2, 0, "00:09")));
        depth.setNarrative(List.of("1회 모두 목표 깊이를 안정적으로 지켰어요.",
                "1회는 바닥 자세가 확인되지 않아 인정되지 않았어요 — 무릎 76° 이하까지 앉아 잠깐 멈췄다 일어나세요."));
        depth.setTargetKneeDeg(76.2);
        SessionDetailedAnalysis stored = new SessionDetailedAnalysis(null,
                List.of(RepSyncRateDto.counted(1, 88.0, "00:05", 5.2),
                        RepSyncRateDto.rejected(1, 41.5, "00:09", 9.4, "DEPTH_NOT_CONFIRMED")),
                depth, null, null, null, null, 1, Map.of("DEPTH_NOT_CONFIRMED", 1));
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(stored));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getRejectedReps()).isEqualTo(1);
        assertThat(result.getRejectedRepsByReason()).containsExactly(Map.entry("DEPTH_NOT_CONFIRMED", 1));
        RepSyncRateDto rejected = result.getRepTrend().get(1);
        assertThat(rejected.getRepNumber()).isNull();
        assertThat(rejected.getAttemptNumber()).isEqualTo(1);
        assertThat(rejected.isCounted()).isFalse();
        assertThat(rejected.getRejectReason()).isEqualTo("DEPTH_NOT_CONFIRMED");
        assertThat(rejected.getTimestampSec()).isEqualTo(9.4);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains(
                "\"rejectedReps\":1",
                "\"rejectedRepsByReason\":{\"DEPTH_NOT_CONFIRMED\":1}",
                "{\"repNumber\":null,\"syncRate\":41.5,\"timeStamp\":\"00:09\",\"timestampSec\":9.4,"
                        + "\"counted\":false,\"attemptNumber\":1,\"rejectReason\":\"DEPTH_NOT_CONFIRMED\"}",
                "{\"repNumber\":1,\"syncRate\":88.0,\"timeStamp\":\"00:05\",\"timestampSec\":5.2,"
                        + "\"counted\":true,\"attemptNumber\":null,\"rejectReason\":null}",
                "{\"repNumber\":null,\"minKneeAngle\":101.2,\"depthScore\":0,\"timeStamp\":\"00:09\","
                        + "\"counted\":false,\"attemptNumber\":1}");
    }

    @Test
    @DisplayName("precompute 이전 리포트 재계산에도 미인정 시도가 섞인다 — 같은 조립")
    void recompute_includesRejectedAttempts() {
        report.setDetailedAnalysis(null);
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(1L, 0.0, 50.0, 1, 100.0, null, null, null));
        when(poseDataRepository.findFramesBySessionId(eq(SESSION_ID), any())).thenReturn(frames);
        List<com.shadowfit.dto.report.RejectedAttemptProjection> rejected = List.of(
                new com.shadowfit.dto.report.RejectedAttemptProjection(1,
                        com.shadowfit.model.exercise.RejectedAttemptReason.SPLIT_STANCE, 30.0, 110.0, 20.0, 4.0));
        when(rejectedAttemptRepository.findSummariesBySessionId(SESSION_ID)).thenReturn(rejected);

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        verify(sessionAnalysisCalculator).calculateRepTrend(frames, rejected);
        verify(squatDepthAnalysisCalculator).calculate(session, frames, rejected);
        verify(squatTrunkLeanAnalysisCalculator).calculate(session, frames, rejected);
        assertThat(result.getRejectedReps()).isEqualTo(1);
        assertThat(result.getRejectedRepsByReason()).containsExactly(Map.entry("SPLIT_STANCE", 1));
    }
}
