package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.TrunkLeanAnalysisDto;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SquatDepthMode;
import com.shadowfit.model.exercise.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SquatTrunkLeanAnalysisCalculator 단위 테스트 (2026-09-28).
 *
 * <p>회귀 대상은 SquatDepthAnalysisCalculatorTest와 같은 세 가지 — ① 채점 공식의 경계값,
 * ② 등급 5단계 경계, ③ 코멘트 우선순위 — 에 더해, rep 점수가 "최솟값"이 아니라 "그 rep에
 * 속한 프레임들의 평균"임을 고정하는 축 하나가 추가된다(깊이 계산기와의 핵심 차이).
 */
@DisplayName("SquatTrunkLeanAnalysisCalculator 테스트")
class SquatTrunkLeanAnalysisCalculatorTest {

    private SquatTrunkLeanAnalysisCalculator calculator;
    private Session squatSession;
    private long nextPoseDataId;

    @BeforeEach
    void setUp() {
        calculator = new SquatTrunkLeanAnalysisCalculator();
        nextPoseDataId = 1L;
        squatSession = session(1L);
    }

    private Session session(long exerciseId) {
        Category category = Category.builder().id(1L).name("LOWER").build();
        Exercise exercise = Exercise.builder()
                .id(exerciseId).name(exerciseId == 1L ? "스쿼트" : "전방 런지").category(category)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build();
        return Session.builder()
                .id(1L).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).squatDepthMode(SquatDepthMode.FULL)
                .build();
    }

    private PoseFrameProjection frame(int repNumber, double torsoTiltDeg) {
        return new PoseFrameProjection(nextPoseDataId++, (double) repNumber, 80.0, repNumber, 90.0, null, torsoTiltDeg, null);
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스쿼트가 아니면(exercise_id != 1) null")
    void nonSquatExercise_returnsNull() {
        Session lungeSession = session(2L);
        assertThat(calculator.calculate(lungeSession, List.of(frame(1, 10.0)))).isNull();
    }

    @Test
    @DisplayName("프레임이 없으면 null")
    void emptyFrames_returnsNull() {
        assertThat(calculator.calculate(squatSession, List.of())).isNull();
    }

    @Test
    @DisplayName("유효한 체간 기울기(non-null) 데이터가 있는 rep이 하나도 없으면 null")
    void noValidTilt_returnsNull() {
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, 80.0, 1, 90.0, null, null, null));

        assertThat(calculator.calculate(squatSession, frames)).isNull();
    }

    // ── 채점 경계값 ────────────────────────────────────────────────────

    @Test
    @DisplayName("체간 기울기 35도 이하는 만점(정상 스쿼트 실측 약 30도)")
    void atOrBelowNaturalLimit_scoresMax() {
        TrunkLeanAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 30.0)));
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("체간 기울기 45도(명백한 과도 경계)는 60점")
    void atExcessiveThreshold_scores60() {
        TrunkLeanAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 45.0)));
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("체간 기울기 40도(35~45 중간)는 80점")
    void atMidpoint_scores80() {
        TrunkLeanAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 40.0)));
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(80);
    }

    @Test
    @DisplayName("rep 점수는 그 rep에 속한 프레임들의 체간 기울기 '평균'이다 — 최솟값이 아니다")
    void repScore_isAverageOfFramesInRep_notMinimum() {
        // 같은 rep 안에 20도와 50도 프레임이 섞이면 평균 35도로 채점돼야 한다(만점 경계).
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, 80.0, 1, 90.0, null, 20.0, null),
                new PoseFrameProjection(2L, 1.0, 80.0, 1, 90.0, null, 50.0, null));

        TrunkLeanAnalysisDto result = calculator.calculate(squatSession, frames);

        assertThat(result.getRepTrunkLeans().get(0).getAvgTiltDeg()).isEqualTo(35.0);
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(100);
    }

    // ── 등급 5단계 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("등급: 세션 평균 점수에 따라 EXCELLENT~POOR 5단계")
    void grade_fiveTiers() {
        assertThat(gradeFor(95)).isEqualTo("EXCELLENT");
        assertThat(gradeFor(80)).isEqualTo("GOOD");
        assertThat(gradeFor(65)).isEqualTo("FAIR");
        assertThat(gradeFor(45)).isEqualTo("NEEDS_WORK");
        assertThat(gradeFor(20)).isEqualTo("POOR");
    }

    private String gradeFor(int targetScore) {
        double tilt = targetScore >= 60
                ? 35 + (100 - targetScore) / 40.0 * 10
                : 45 + (60 - targetScore) / 3.0;
        TrunkLeanAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, tilt)));
        return result.getTiltGrade();
    }

    // ── 세션 평균 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 점수는 회차별 점수의 평균(반올림)")
    void sessionScore_isAverageOfRepScores() {
        // rep1: tilt=30 -> 100점, rep2: tilt=45 -> 60점 => 평균 80
        TrunkLeanAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 30.0), frame(2, 45.0)));
        assertThat(result.getTiltScore()).isEqualTo(80);
    }

    // ── 회차별 코멘트 우선순위 ───────────────────────────────────────────

    @Test
    @DisplayName("코멘트 ①: 앞 회차 좋고 바로 다음 회차가 크게 나빠지면 그 두 회차를 지목한다")
    void narrative_adjacentDrop_mentionsBothReps() {
        // rep1: tilt=30(100점) -> rep2: tilt=55(30점, 낙차 70) -> 트리거
        TrunkLeanAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 30.0), frame(2, 55.0)));

        assertThat(result.getNarrative()).anySatisfy(line ->
                assertThat(line).contains("1회차").contains("2회차"));
    }

    @Test
    @DisplayName("코멘트 ②: 세션 전체 등급이 FAIR 미만이면 전반적 경고 코멘트를 낸다")
    void narrative_lowOverallGrade_addsGeneralComment() {
        TrunkLeanAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 60.0), frame(2, 60.0), frame(3, 60.0)));

        assertThat(result.getTiltScore()).isLessThan(60);
        assertThat(result.getNarrative()).anySatisfy(line -> assertThat(line).contains("전반적으로"));
    }

    @Test
    @DisplayName("코멘트 ③: 모든 회차가 고르게 양호하면 회차 언급 없는 한 줄로 폴백한다")
    void narrative_allGood_fallsBackToSingleLine() {
        TrunkLeanAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 30.0), frame(2, 32.0), frame(3, 28.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회");
    }

    @Test
    @DisplayName("코멘트는 최대 4문장")
    void narrative_cappedAtFour() {
        TrunkLeanAnalysisDto result = calculator.calculate(squatSession, List.of(
                frame(1, 30.0), frame(2, 55.0), frame(3, 32.0), frame(4, 56.0), frame(5, 60.0)));

        assertThat(result.getNarrative()).hasSizeLessThanOrEqualTo(4);
    }
}
