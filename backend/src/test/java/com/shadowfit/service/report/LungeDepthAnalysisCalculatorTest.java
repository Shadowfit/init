package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.LungeDepthAnalysisDto;
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
 * LungeDepthAnalysisCalculator 단위 테스트 (2026-09-27).
 *
 * <p>회귀 대상은 SquatDepthAnalysisCalculatorTest와 같은 세 가지에 더해, 런지 전용 축 하나 —
 * ① 채점 공식의 경계값, ② 등급 5단계 경계, ③ 코멘트 우선순위(어떤 회차를 지목했는가),
 * ④ 좌우 평균·비대칭 판정(스쿼트에는 없는 축).
 */
@DisplayName("LungeDepthAnalysisCalculator 테스트")
class LungeDepthAnalysisCalculatorTest {

    private LungeDepthAnalysisCalculator calculator;
    private Session lungeSession;
    private long nextPoseDataId;

    @BeforeEach
    void setUp() {
        calculator = new LungeDepthAnalysisCalculator();
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

    private PoseFrameProjection frame(int repNumber, String side, double minKneeAngle) {
        return new PoseFrameProjection(nextPoseDataId++, (double) repNumber, null, repNumber, minKneeAngle, side);
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("런지가 아니면(exercise_id != 2) null")
    void nonLungeExercise_returnsNull() {
        Session squatSession = session(1L);
        List<PoseFrameProjection> frames = List.of(frame(1, "left", 90.0));

        assertThat(calculator.calculate(squatSession, frames)).isNull();
    }

    @Test
    @DisplayName("프레임이 없으면 null")
    void emptyFrames_returnsNull() {
        assertThat(calculator.calculate(lungeSession, List.of())).isNull();
    }

    @Test
    @DisplayName("유효한 무릎각(>0)이 있는 rep이 하나도 없으면 null")
    void noValidKneeAngle_returnsNull() {
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, null, 1, 0.0, "left"),
                new PoseFrameProjection(2L, 1.0, null, 1, null, "left"));

        assertThat(calculator.calculate(lungeSession, frames)).isNull();
    }

    // ── 채점 경계값 (스쿼트 FULL과 같은 모양) ─────────────────────────────

    @Test
    @DisplayName("무릎 100도 이하는 만점")
    void atOrBelowTarget_scoresMax() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, "left", 90.0)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("무릎 115도(실질 진입 경계)는 60점")
    void atEntryBoundary_scores60() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, "left", 115.0)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("무릎 107.5도(100~115 중간)는 80점")
    void atMidpoint_scores80() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, "left", 107.5)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(80);
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
        double knee = targetScore >= 60
                ? 100 + (100 - targetScore) / 40.0 * 15
                : 115 + (60 - targetScore) / 2.0;
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, "left", knee)));
        return result.getDepthGrade();
    }

    // ── 세션 평균 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 점수는 회차별 점수의 평균(반올림)")
    void sessionScore_isAverageOfRepScores() {
        LungeDepthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, "left", 90.0), frame(2, "right", 115.0)));
        assertThat(result.getDepthScore()).isEqualTo(80);
    }

    // ── 좌우 평균·비대칭 (런지 전용) ────────────────────────────────────

    @Test
    @DisplayName("좌우 평균은 그 다리로 시작한 회차만으로 계산한다")
    void sideAverages_onlyIncludeMatchingSide() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, "left", 60.0), frame(2, "left", 70.0), frame(3, "right", 80.0)));

        assertThat(result.getLeftAvgKneeAngle()).isEqualTo(65.0);
        assertThat(result.getRightAvgKneeAngle()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("한쪽 다리로 시작한 회차가 없으면 그쪽 평균은 null")
    void sideAverage_nullWhenNoRepsOnThatSide() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, "left", 60.0)));

        assertThat(result.getLeftAvgKneeAngle()).isEqualTo(60.0);
        assertThat(result.getRightAvgKneeAngle()).isNull();
    }

    @Test
    @DisplayName("좌우 평균 차이가 10도 이상이면 비대칭 코멘트를 낸다")
    void narrative_asymmetry_mentionsBothSides() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, "left", 60.0), frame(2, "right", 90.0)));

        assertThat(result.getNarrative()).anySatisfy(line ->
                assertThat(line).contains("오른쪽").contains("왼쪽"));
    }

    @Test
    @DisplayName("좌우 평균 차이가 10도 미만이면 비대칭 코멘트를 내지 않는다")
    void narrative_balanced_noAsymmetryComment() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, "left", 60.0), frame(2, "right", 65.0)));

        assertThat(result.getNarrative()).noneSatisfy(line ->
                assertThat(line).contains("균형을 맞춰보세요"));
    }

    // ── 회차별 코멘트 우선순위 ───────────────────────────────────────────

    @Test
    @DisplayName("코멘트: 같은 다리 안에서 앞 회차 좋고 바로 다음 회차가 크게 떨어지면 그 두 회차를 지목한다")
    void narrative_adjacentDrop_sameLeg_mentionsBothReps() {
        // rep1(left,90=100점) -> rep2(left,118=54점, 낙차 46) -> 트리거. 둘 다 왼쪽이라
        // rightAvg가 null이 되어 비대칭 코멘트(우선순위 ①)는 애초에 계산되지 않는다.
        LungeDepthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, "left", 90.0), frame(2, "left", 118.0)));

        assertThat(result.getNarrative()).anySatisfy(line ->
                assertThat(line).contains("1회차").contains("2회차"));
    }

    @Test
    @DisplayName("코멘트: 다리가 바뀌는 지점은 인접 회차 대비에서 비교하지 않는다(워킹 런지)")
    void narrative_adjacentDrop_doesNotCompareAcrossLegs() {
        // rep1(left,100=100점) -> rep2(right,106=84점, 낙차 16) — 같은 다리였다면 ②가 트리거될
        // 낙차지만 다리가 다르므로 비교하지 않는다. 좌우 평균 차이도 6도로 10도 미만이라
        // 비대칭 코멘트(①)도 안 섞여 이 테스트의 관심사(② 하나)만 본다.
        LungeDepthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, "left", 100.0), frame(2, "right", 106.0)));

        assertThat(result.getNarrative()).noneSatisfy(line ->
                assertThat(line).contains("1회차").contains("2회차"));
    }

    @Test
    @DisplayName("코멘트: 통과했지만 평균 깊이가 얕으면(85도 초과) 아슬아슬 코멘트")
    void narrative_closeCall_whenShallowButPassing() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, "left", 89.0), frame(2, "left", 88.0)));

        assertThat(result.getNarrative().get(0)).contains("여유 있게 통과하지는 않았어요");
    }

    @Test
    @DisplayName("코멘트: 모든 회차가 고르게 깊고 좌우도 균형이면 회차 언급 없는 한 줄로 폴백한다")
    void narrative_allGood_fallsBackToSingleLine() {
        LungeDepthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, "left", 60.0), frame(2, "right", 62.0), frame(3, "left", 61.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회");
    }

    @Test
    @DisplayName("코멘트는 최대 4문장")
    void narrative_cappedAtFour() {
        LungeDepthAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, "left", 60.0), frame(2, "left", 118.0), frame(3, "left", 62.0),
                frame(4, "left", 119.0), frame(5, "right", 95.0)));

        assertThat(result.getNarrative()).hasSizeLessThanOrEqualTo(4);
    }
}
