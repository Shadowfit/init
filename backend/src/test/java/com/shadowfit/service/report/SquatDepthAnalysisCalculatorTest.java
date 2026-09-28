package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto;
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
 * SquatDepthAnalysisCalculator 단위 테스트 (2026-09-21).
 *
 * <p>회귀 대상은 세 가지다 — ① 채점 공식의 경계값이 formula와 정확히 일치하는가,
 * ② 등급 5단계 경계, ③ 코멘트 생성기가 트리거 우선순위대로 정확히 하나를 고르는가(문구 자체가
 * 아니라 "어떤 회차를 지목했는가"를 본다 — 문구는 잠정이라 SessionAnalysisCalculator의 worst
 * reason과 같은 이유로 파싱하지 않는다).
 */
@DisplayName("SquatDepthAnalysisCalculator 테스트")
class SquatDepthAnalysisCalculatorTest {

    private SquatDepthAnalysisCalculator calculator;
    private Session squatSession;
    private long nextPoseDataId;

    @BeforeEach
    void setUp() {
        calculator = new SquatDepthAnalysisCalculator();
        nextPoseDataId = 1L;
        squatSession = session(1L, SquatDepthMode.FULL);
    }

    private Session session(long exerciseId, SquatDepthMode mode) {
        Category category = Category.builder().id(1L).name("LOWER").build();
        Exercise exercise = Exercise.builder()
                .id(exerciseId).name(exerciseId == 1L ? "스쿼트" : "전방 런지").category(category)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build();
        return Session.builder()
                .id(1L).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).squatDepthMode(mode)
                .build();
    }

    private PoseFrameProjection frame(int repNumber, double minKneeAngle) {
        return new PoseFrameProjection(nextPoseDataId++, (double) repNumber, 80.0, repNumber, minKneeAngle, null, null);
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스쿼트가 아니면(exercise_id != 1) null")
    void nonSquatExercise_returnsNull() {
        Session lungeSession = session(2L, SquatDepthMode.FULL);
        List<PoseFrameProjection> frames = List.of(frame(1, 90.0));

        assertThat(calculator.calculate(lungeSession, frames)).isNull();
    }

    @Test
    @DisplayName("프레임이 없으면 null")
    void emptyFrames_returnsNull() {
        assertThat(calculator.calculate(squatSession, List.of())).isNull();
    }

    @Test
    @DisplayName("유효한 무릎각(>0)이 있는 rep이 하나도 없으면 null")
    void noValidKneeAngle_returnsNull() {
        List<PoseFrameProjection> frames = List.of(
                new PoseFrameProjection(1L, 1.0, 80.0, 1, 0.0, null, null),
                new PoseFrameProjection(2L, 1.0, 80.0, 1, null, null, null));

        assertThat(calculator.calculate(squatSession, frames)).isNull();
    }

    // ── FULL 모드 채점 경계값 ────────────────────────────────────────────

    @Test
    @DisplayName("FULL: 무릎 100도 이하는 만점")
    void full_atOrBelowTarget_scoresMax() {
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 90.0)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("FULL: 무릎 115도(실질 진입 경계)는 60점")
    void full_atEntryBoundary_scores60() {
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 115.0)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("FULL: 무릎 107.5도(100~115 중간)는 80점")
    void full_atMidpoint_scores80() {
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 107.5)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(80);
    }

    // ── HALF 모드 채점 경계값 ────────────────────────────────────────────

    @Test
    @DisplayName("HALF: 무릎 127.5도(밴드 중심)는 만점")
    void half_atCenter_scoresMax() {
        Session halfSession = session(1L, SquatDepthMode.HALF);
        SquatDepthAnalysisDto result = calculator.calculate(halfSession, List.of(frame(1, 127.5)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("HALF: 무릎 115도(밴드 얕은 쪽 경계)는 70점")
    void half_atShallowEdge_scores70() {
        Session halfSession = session(1L, SquatDepthMode.HALF);
        SquatDepthAnalysisDto result = calculator.calculate(halfSession, List.of(frame(1, 115.0)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(70);
    }

    @Test
    @DisplayName("HALF: 무릎 140도(밴드 깊은 쪽 경계)도 70점 — 대칭")
    void half_atDeepEdge_scores70() {
        Session halfSession = session(1L, SquatDepthMode.HALF);
        SquatDepthAnalysisDto result = calculator.calculate(halfSession, List.of(frame(1, 140.0)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(70);
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

    /**
     * 단일 rep의 무릎각을 역산해 원하는 점수를 만들고 등급만 본다(FULL 모드).
     * scoreFull의 두 구간을 역산 대상 점수에 따라 나눠 쓴다 —
     * 60~100점: knee∈[100,115] 선형 구간, 60점 미만: knee>115 방어 구간(기울기 -2).
     */
    private String gradeFor(int targetScore) {
        double knee = targetScore >= 60
                ? 100 + (100 - targetScore) / 40.0 * 15         // score = 100 - (knee-100)/15*40
                : 115 + (60 - targetScore) / 2.0;                // score = 60 - (knee-115)*2
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, knee)));
        return result.getDepthGrade();
    }

    // ── 세션 평균 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 점수는 회차별 점수의 평균(반올림)")
    void sessionScore_isAverageOfRepScores() {
        // rep1: knee=90 -> 100점, rep2: knee=115 -> 60점 => 평균 80
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 90.0), frame(2, 115.0)));
        assertThat(result.getDepthScore()).isEqualTo(80);
    }

    // ── 회차별 코멘트 우선순위 ───────────────────────────────────────────

    @Test
    @DisplayName("코멘트 ①: 앞 회차 좋고 바로 다음 회차가 크게 떨어지면 그 두 회차를 지목한다")
    void narrative_adjacentDrop_mentionsBothReps() {
        // rep1: knee=90(100점) -> rep2: knee=118(54점, 낙차 46) -> 트리거
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 90.0), frame(2, 118.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("1회차").contains("2회차");
    }

    @Test
    @DisplayName("코멘트 ②: HALF 모드에서 풀 스쿼트급으로 깊이 앉은 회차를 지목한다")
    void narrative_halfModeTooDeep_mentionsRep() {
        Session halfSession = session(1L, SquatDepthMode.HALF);
        // rep1,2 는 밴드 중심으로 안정적, rep3 만 무릎 105(풀 스쿼트급)로 깊게 앉음
        SquatDepthAnalysisDto result = calculator.calculate(halfSession,
                List.of(frame(1, 127.5), frame(2, 127.5), frame(3, 105.0)));

        assertThat(result.getNarrative()).anySatisfy(line -> assertThat(line).contains("3회차"));
    }

    @Test
    @DisplayName("코멘트 ⑤: 모든 회차가 고르게 양호하면 회차 언급 없는 한 줄로 폴백한다")
    void narrative_allGood_fallsBackToSingleLine() {
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 95.0), frame(2, 92.0), frame(3, 90.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회");
    }

    @Test
    @DisplayName("코멘트는 최대 4문장")
    void narrative_cappedAtFour() {
        // 편차가 크게 나도록 회차마다 깊이를 들쭉날쭉하게 구성 — 여러 트리거가 동시에 걸려도 4개 이하.
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(
                frame(1, 90.0), frame(2, 118.0), frame(3, 92.0), frame(4, 119.0), frame(5, 91.0)));

        assertThat(result.getNarrative()).hasSizeLessThanOrEqualTo(4);
    }
}
