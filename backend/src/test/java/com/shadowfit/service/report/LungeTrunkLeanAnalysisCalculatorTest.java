package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.TrunkLeanAnalysisDto;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LungeTrunkLeanAnalysisCalculator 단위 테스트 (2026-09-28).
 *
 * <p>SquatTrunkLeanAnalysisCalculatorTest와 같은 축을 검증하되, 임계값이 스쿼트(20/35)보다
 * 훨씬 관대한 런지 전용 밴드(35/55)로 채점되는지를 고정한다 — 런지의 의도적 전방 기울임은
 * 정상 기법이라(계산기 클래스 javadoc 참고) 스쿼트와 같은 경계를 쓰면 과잉 감점된다.
 */
@DisplayName("LungeTrunkLeanAnalysisCalculator 테스트")
class LungeTrunkLeanAnalysisCalculatorTest {

    private LungeTrunkLeanAnalysisCalculator calculator;
    private Session lungeSession;
    private long nextPoseDataId;

    @BeforeEach
    void setUp() {
        calculator = new LungeTrunkLeanAnalysisCalculator();
        nextPoseDataId = 1L;
        lungeSession = session(2L);
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
                .status(Status.COMPLETED)
                .build();
    }

    private PoseFrameProjection frame(int repNumber, double torsoTiltDeg) {
        return new PoseFrameProjection(nextPoseDataId++, (double) repNumber, null, repNumber, 90.0, "left", torsoTiltDeg, null);
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("런지가 아니면(exercise_id != 2) null")
    void nonLungeExercise_returnsNull() {
        Session squatSession = session(1L);
        assertThat(calculator.calculate(squatSession, List.of(frame(1, 10.0)))).isNull();
    }

    @Test
    @DisplayName("프레임이 없으면 null")
    void emptyFrames_returnsNull() {
        assertThat(calculator.calculate(lungeSession, List.of())).isNull();
    }

    @Test
    @DisplayName("유효한 체간 기울기(non-null) 데이터가 있는 rep이 하나도 없으면 null")
    void noValidTilt_returnsNull() {
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, null, 1, 90.0, "left", null, null));

        assertThat(calculator.calculate(lungeSession, frames)).isNull();
    }

    // ── 채점 경계값 (스쿼트보다 관대한 밴드) ────────────────────────────

    @Test
    @DisplayName("체간 기울기 35도 이하는 만점 — 스쿼트라면 60점일 값")
    void atNaturalLimit_scoresMax() {
        TrunkLeanAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, 35.0)));
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("체간 기울기 55도(과도 경계)는 60점")
    void atExcessiveThreshold_scores60() {
        TrunkLeanAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, 55.0)));
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("체간 기울기 45도(35~55 중간)는 80점")
    void atMidpoint_scores80() {
        TrunkLeanAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, 45.0)));
        assertThat(result.getRepTrunkLeans().get(0).getTiltScore()).isEqualTo(80);
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
                ? 35 + (100 - targetScore) / 40.0 * 20
                : 55 + (60 - targetScore) / 3.0;
        TrunkLeanAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, tilt)));
        return result.getTiltGrade();
    }

    // ── 세션 평균 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 점수는 회차별 점수의 평균(반올림)")
    void sessionScore_isAverageOfRepScores() {
        // rep1: tilt=35 -> 100점, rep2: tilt=55 -> 60점 => 평균 80
        TrunkLeanAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 35.0), frame(2, 55.0)));
        assertThat(result.getTiltScore()).isEqualTo(80);
    }

    // ── 회차별 코멘트 우선순위 ───────────────────────────────────────────

    @Test
    @DisplayName("코멘트 ①: 앞 회차 좋고 바로 다음 회차가 크게 나빠지면 그 두 회차를 지목한다")
    void narrative_adjacentDrop_mentionsBothReps() {
        // rep1: tilt=35(100점) -> rep2: tilt=65(30점, 낙차 70) -> 트리거
        TrunkLeanAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 35.0), frame(2, 65.0)));

        assertThat(result.getNarrative()).anySatisfy(line ->
                assertThat(line).contains("1회차").contains("2회차"));
    }

    @Test
    @DisplayName("코멘트 ②: 세션 전체 등급이 FAIR 미만이면 전반적 경고 코멘트를 낸다")
    void narrative_lowOverallGrade_addsGeneralComment() {
        TrunkLeanAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 70.0), frame(2, 70.0), frame(3, 70.0)));

        assertThat(result.getTiltScore()).isLessThan(60);
        assertThat(result.getNarrative()).anySatisfy(line -> assertThat(line).contains("코어"));
    }

    @Test
    @DisplayName("코멘트 ③: 모든 회차가 고르게 양호하면 회차 언급 없는 한 줄로 폴백한다")
    void narrative_allGood_fallsBackToSingleLine() {
        TrunkLeanAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 20.0), frame(2, 25.0), frame(3, 22.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회");
    }

    @Test
    @DisplayName("코멘트는 최대 4문장")
    void narrative_cappedAtFour() {
        TrunkLeanAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, 35.0), frame(2, 65.0), frame(3, 36.0), frame(4, 66.0), frame(5, 70.0)));

        assertThat(result.getNarrative()).hasSizeLessThanOrEqualTo(4);
    }
}
