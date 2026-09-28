package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.LungeStepLengthAnalysisDto;
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
 * LungeStepLengthAnalysisCalculator 단위 테스트 (2026-09-28).
 *
 * <p>회귀 대상은 다른 계산기들과 같은 세 가지 — ① 채점 공식의 경계값, ② 등급 5단계 경계,
 * ③ 코멘트 우선순위 — 에 더해, rep의 대표값이 "그 rep에서 무릎이 가장 깊게 굽혀진 프레임"의
 * staggerRatio임을 고정하는 축 하나가 추가된다(LungeDepthAnalysisCalculator와 같은 대표
 * 프레임 선택 재사용).
 */
@DisplayName("LungeStepLengthAnalysisCalculator 테스트")
class LungeStepLengthAnalysisCalculatorTest {

    private LungeStepLengthAnalysisCalculator calculator;
    private Session lungeSession;
    private long nextPoseDataId;

    @BeforeEach
    void setUp() {
        calculator = new LungeStepLengthAnalysisCalculator();
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

    private PoseFrameProjection frame(int repNumber, double staggerRatio) {
        return new PoseFrameProjection(nextPoseDataId++, (double) repNumber, null, repNumber, 90.0, "left", null, staggerRatio);
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("런지가 아니면(exercise_id != 2) null")
    void nonLungeExercise_returnsNull() {
        Session squatSession = session(1L);
        assertThat(calculator.calculate(squatSession, List.of(frame(1, 1.0)))).isNull();
    }

    @Test
    @DisplayName("프레임이 없으면 null")
    void emptyFrames_returnsNull() {
        assertThat(calculator.calculate(lungeSession, List.of())).isNull();
    }

    @Test
    @DisplayName("유효한 보폭(non-null staggerRatio) 데이터가 있는 rep이 하나도 없으면 null")
    void noValidStagger_returnsNull() {
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, null, 1, 90.0, "left", null, null));

        assertThat(calculator.calculate(lungeSession, frames)).isNull();
    }

    // ── 채점 경계값 ────────────────────────────────────────────────────

    @Test
    @DisplayName("stagger 1.0 이상은 만점")
    void atOrAboveAdequate_scoresMax() {
        LungeStepLengthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, 1.2)));
        assertThat(result.getRepStepLengths().get(0).getStepScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("stagger 0.6(짧은 보폭 진입 경계)은 60점")
    void atShortEntry_scores60() {
        LungeStepLengthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, 0.6)));
        assertThat(result.getRepStepLengths().get(0).getStepScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("stagger 0.8(0.6~1.0 중간)은 80점")
    void atMidpoint_scores80() {
        LungeStepLengthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, 0.8)));
        assertThat(result.getRepStepLengths().get(0).getStepScore()).isEqualTo(80);
    }

    @Test
    @DisplayName("rep 대표값은 그 rep에서 무릎이 가장 깊게 굽혀진 프레임의 staggerRatio다")
    void repRepresentative_isDeepestKneeFrame() {
        // 같은 rep 안에 무릎 120도(얕음, stagger 1.2)와 90도(깊음, stagger 0.7) 프레임이 섞이면
        // 더 깊은(90도) 프레임의 stagger인 0.7을 대표로 써야 한다.
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, null, 1, 120.0, "left", null, 1.2),
                new PoseFrameProjection(2L, 1.0, null, 1, 90.0, "left", null, 0.7));

        LungeStepLengthAnalysisDto result = calculator.calculate(lungeSession, frames);

        assertThat(result.getRepStepLengths().get(0).getStaggerRatio()).isEqualTo(0.7);
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
        double stagger = targetScore >= 60
                ? 1.0 - (100 - targetScore) / 40.0 * 0.4
                : 0.6 - (60 - targetScore) / 100.0;
        LungeStepLengthAnalysisDto result = calculator.calculate(lungeSession, List.of(frame(1, stagger)));
        return result.getStepGrade();
    }

    // ── 세션 평균 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 점수는 회차별 점수의 평균(반올림)")
    void sessionScore_isAverageOfRepScores() {
        // rep1: stagger=1.2 -> 100점, rep2: stagger=0.6 -> 60점 => 평균 80
        LungeStepLengthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 1.2), frame(2, 0.6)));
        assertThat(result.getStepScore()).isEqualTo(80);
    }

    // ── 회차별 코멘트 우선순위 ───────────────────────────────────────────

    @Test
    @DisplayName("코멘트 ①: 앞 회차 넉넉하고 바로 다음 회차가 크게 짧아지면 그 두 회차를 지목한다")
    void narrative_adjacentDrop_mentionsBothReps() {
        // rep1: stagger=1.2(100점) -> rep2: stagger=0.5(30점, 낙차 70) -> 트리거
        LungeStepLengthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 1.2), frame(2, 0.5)));

        assertThat(result.getNarrative()).anySatisfy(line ->
                assertThat(line).contains("1회차").contains("2회차"));
    }

    @Test
    @DisplayName("코멘트 ②: 세션 전체 등급이 FAIR 미만이면 전반적 경고 코멘트를 낸다")
    void narrative_lowOverallGrade_addsGeneralComment() {
        LungeStepLengthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 0.4), frame(2, 0.4), frame(3, 0.4)));

        assertThat(result.getStepScore()).isLessThan(60);
        assertThat(result.getNarrative()).anySatisfy(line -> assertThat(line).contains("앞발"));
    }

    @Test
    @DisplayName("코멘트 ③: 모든 회차가 고르게 양호하면 회차 언급 없는 한 줄로 폴백한다")
    void narrative_allGood_fallsBackToSingleLine() {
        LungeStepLengthAnalysisDto result = calculator.calculate(
                lungeSession, List.of(frame(1, 1.1), frame(2, 1.2), frame(3, 1.15)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회");
    }

    @Test
    @DisplayName("코멘트는 최대 4문장")
    void narrative_cappedAtFour() {
        LungeStepLengthAnalysisDto result = calculator.calculate(lungeSession, List.of(
                frame(1, 1.2), frame(2, 0.4), frame(3, 1.2), frame(4, 0.4), frame(5, 0.4)));

        assertThat(result.getNarrative()).hasSizeLessThanOrEqualTo(4);
    }
}
