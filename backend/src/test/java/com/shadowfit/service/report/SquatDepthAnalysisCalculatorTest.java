package com.shadowfit.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.RejectedAttemptProjection;
import com.shadowfit.dto.report.detailreport.RepDepthDto;
import com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.RejectedAttemptReason;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SquatDepthMode;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SquatDepthAnalysisCalculator 단위 테스트 (2026-09-21, FULL 규칙 2026-10-21 개정).
 *
 * <p>회귀 대상은 네 가지다 — ① FULL 목표가 기준(시범) 최저 무릎각 + 허용 오차에서 유도되는가(기준이 없으면 폴백),
 * ② 채점 공식의 경계값이 formula와 정확히 일치하는가, ③ 등급 5단계 경계, ④ 코멘트 생성기가 트리거 우선순위대로
 * 정확히 하나를 고르는가(문구 자체가 아니라 "어떤 회차를 지목했는가"를 본다 — 문구는 잠정이라
 * SessionAnalysisCalculator의 worst reason과 같은 이유로 파싱하지 않는다).
 */
@DisplayName("SquatDepthAnalysisCalculator 테스트")
class SquatDepthAnalysisCalculatorTest {

    private ExerciseReferenceRepository referenceRepository;
    private SquatDepthAnalysisCalculator calculator;
    private Session squatSession;
    private long nextPoseDataId;

    @BeforeEach
    void setUp() {
        referenceRepository = mock(ExerciseReferenceRepository.class);
        // 현재 기준(3D 마네킹 시범)과 같은 깊이 — 서 있는 프레임 170°, 가장 깊은 프레임 71.2° → 목표 76.2°
        when(referenceRepository.findByExerciseId(1L))
                .thenReturn(List.of(referenceFrame(170.0), referenceFrame(71.2), referenceFrame(120.0)));
        calculator = new SquatDepthAnalysisCalculator(referenceRepository, new ObjectMapper());
        nextPoseDataId = 1L;
        squatSession = session(1L, SquatDepthMode.FULL);
    }

    private Session session(long exerciseId, SquatDepthMode mode) {
        Category category = Category.builder().id(1L).name("LOWER").build();
        Exercise exercise = Exercise.builder()
                .id(exerciseId).name(exerciseId == 1L ? "스쿼트" : "전방 런지").category(category)
                .expectedDurationMinutes(15)
                .build();
        return Session.builder()
                .id(1L).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).squatDepthMode(mode)
                .build();
    }

    private PoseFrameProjection frame(int repNumber, double minKneeAngle) {
        return new PoseFrameProjection(nextPoseDataId++, (double) repNumber, 80.0, repNumber, minKneeAngle, null, null, null);
    }

    /**
     * 무릎각이 정확히 {@code kneeDeg} 인 기준 프레임 한 장. 무릎 (0.5,0.5)·발목은 바로 아래 (0.5,0.8),
     * 엉덩이는 정강이와 {@code kneeDeg} 를 이루는 자리 — 양쪽 다리 같은 좌표, 화면 비율 표식 없음(1.0).
     */
    private static ExerciseReference referenceFrame(double kneeDeg) {
        double rad = Math.toRadians(kneeDeg);
        double hipX = 0.5 + 0.3 * Math.sin(rad), hipY = 0.5 + 0.3 * Math.cos(rad);
        String json = String.format(Locale.ROOT,
                "[{\"index\":23,\"x\":%.6f,\"y\":%.6f},{\"index\":25,\"x\":0.5,\"y\":0.5},{\"index\":27,\"x\":0.5,\"y\":0.8},"
                        + "{\"index\":24,\"x\":%.6f,\"y\":%.6f},{\"index\":26,\"x\":0.5,\"y\":0.5},{\"index\":28,\"x\":0.5,\"y\":0.8}]",
                hipX, hipY, hipX, hipY);
        return ExerciseReference.builder().timestampSec(0.0).jointCoordinates(json).build();
    }

    private int repScore(double minKnee) {
        return calculator.calculate(squatSession, List.of(frame(1, minKnee))).getRepDepths().get(0).getDepthScore();
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스쿼트가 아니면(exercise_id != 1) null — 기준 조회도 안 한다")
    void nonSquatExercise_returnsNull() {
        Session lungeSession = session(2L, SquatDepthMode.FULL);
        List<PoseFrameProjection> frames = List.of(frame(1, 90.0));

        assertThat(calculator.calculate(lungeSession, frames)).isNull();
        verify(referenceRepository, never()).findByExerciseId(anyLong());
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
                new PoseFrameProjection(1L, 1.0, 80.0, 1, 0.0, null, null, null),
                new PoseFrameProjection(2L, 1.0, 80.0, 1, null, null, null, null));

        assertThat(calculator.calculate(squatSession, frames)).isNull();
    }

    // ── FULL 목표: 기준 최저 무릎각 + 허용 오차 ────────────────────────────

    @Test
    @DisplayName("FULL 목표 = 기준 최저 무릎각(71.2) + 5.0 = 76.2 — DTO 에 실린다")
    void full_targetDerivedFromReference() {
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 80.0)));

        assertThat(result.getTargetKneeDeg()).isEqualTo(76.2);
        assertThat(result.getDepthMode()).isEqualTo("FULL");
    }

    @Test
    @DisplayName("FULL 목표는 기준을 따라간다 — 기준이 90° 면 목표 95°, 95°→100점·100°→80점")
    void full_targetFollowsReference() {
        when(referenceRepository.findByExerciseId(1L)).thenReturn(List.of(referenceFrame(165.0), referenceFrame(90.0)));

        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 95.0), frame(2, 100.0)));

        assertThat(result.getTargetKneeDeg()).isEqualTo(95.0);
        assertThat(result.getRepDepths()).extracting(RepDepthDto::getDepthScore).containsExactly(100, 80);
    }

    @Test
    @DisplayName("기준 행이 없으면 폴백 71.2 — 목표 76.2, 85.3°→64점")
    void full_noReference_fallsBackToMannequinDemo() {
        when(referenceRepository.findByExerciseId(1L)).thenReturn(List.of());

        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 85.3)));

        assertThat(result.getTargetKneeDeg()).isEqualTo(76.2);
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(64);
    }

    @Test
    @DisplayName("기준을 잴 수 없으면(깨진 JSON·무릎 랜드마크 없음) 폴백 71.2")
    void full_unmeasurableReference_fallsBack() {
        when(referenceRepository.findByExerciseId(1L)).thenReturn(List.of(
                ExerciseReference.builder().timestampSec(0.0).jointCoordinates("not json").build(),
                ExerciseReference.builder().timestampSec(0.1).jointCoordinates("[{\"index\":0,\"x\":0.5,\"y\":0.1}]").build()));

        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 81.0)));

        assertThat(result.getTargetKneeDeg()).isEqualTo(76.2);
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(81);
    }

    // ── FULL 채점 경계값 (목표 76.2) ─────────────────────────────────────

    @Test
    @DisplayName("FULL: 목표 이하(같거나 더 깊음)는 만점")
    void full_atOrBelowTarget_scoresMax() {
        assertThat(repScore(76.2)).isEqualTo(100);
        assertThat(repScore(60.0)).isEqualTo(100);
    }

    @Test
    @DisplayName("FULL: 목표보다 1도 얕을 때마다 4점 — 81→81, 85.3→64, 93→33, 100→5")
    void full_fourPointsPerDegree() {
        assertThat(repScore(81.0)).isEqualTo(81);
        assertThat(repScore(85.3)).isEqualTo(64);
        assertThat(repScore(93.0)).isEqualTo(33);
        assertThat(repScore(100.0)).isEqualTo(5);
    }

    @Test
    @DisplayName("FULL: 0점 아래로는 안 간다 — AI 반복 경계(115°)에서도 0점")
    void full_clampedAtZero() {
        assertThat(repScore(101.2)).isEqualTo(0);
        assertThat(repScore(115.0)).isEqualTo(0);
    }

    @Test
    @DisplayName("실세션 회귀: 무릎 85°까지만 앉은 회차는 더 이상 100점이 아니다(싱크로율 63.5 와 모순 해소)")
    void full_realSessionRegression_85degIsNotPerfect() {
        List<PoseFrameProjection> frames = new ArrayList<>();
        for (int rep = 1; rep <= 7; rep++) frames.add(frame(rep, 74.0));
        frames.add(frame(8, 85.0));

        SquatDepthAnalysisDto result = calculator.calculate(squatSession, frames);

        assertThat(result.getRepDepths().get(7).getDepthScore()).isEqualTo(65); // 100 − 4×8.8 = 64.8
        assertThat(result.getNarrative()).anySatisfy(line -> assertThat(line).contains("8회차"));
    }

    // ── HALF 모드 채점 경계값 (변경 없음) ─────────────────────────────────

    @Test
    @DisplayName("HALF: 무릎 127.5도(밴드 중심)는 만점 — 목표 무릎각은 null, 기준 조회 안 함")
    void half_atCenter_scoresMax() {
        Session halfSession = session(1L, SquatDepthMode.HALF);
        SquatDepthAnalysisDto result = calculator.calculate(halfSession, List.of(frame(1, 127.5)));
        assertThat(result.getRepDepths().get(0).getDepthScore()).isEqualTo(100);
        assertThat(result.getTargetKneeDeg()).isNull();
        verify(referenceRepository, never()).findByExerciseId(anyLong());
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

    /** 단일 rep의 무릎각을 역산해 원하는 점수를 만들고 등급만 본다(FULL, 목표 76.2): score = 100 − 4×(knee − 76.2). */
    private String gradeFor(int targetScore) {
        double knee = 76.2 + (100 - targetScore) / 4.0;
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, knee)));
        assertThat(result.getDepthScore()).isEqualTo(targetScore);
        return result.getDepthGrade();
    }

    // ── 세션 평균 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 점수는 회차별 점수의 평균(반올림)")
    void sessionScore_isAverageOfRepScores() {
        // rep1: knee=70 -> 100점, rep2: knee=85.3 -> 64점 => 평균 82
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 70.0), frame(2, 85.3)));
        assertThat(result.getDepthScore()).isEqualTo(82);
    }

    // ── 회차별 코멘트 우선순위 ───────────────────────────────────────────

    @Test
    @DisplayName("코멘트 ①: 앞 회차 좋고 바로 다음 회차가 크게 떨어지면 그 두 회차를 지목한다")
    void narrative_adjacentDrop_mentionsBothReps() {
        // rep1: knee=75(100점) -> rep2: knee=85.3(64점, 낙차 36) -> 트리거
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 75.0), frame(2, 85.3)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("1회차").contains("2회차").contains("시범 깊이");
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
    @DisplayName("코멘트 ③: 60점 미만 회차 중 가장 얕은 회차를 «목표 깊이보다 얕았어요» 로 지목한다")
    void narrative_shallowRep_mentionsShallowest() {
        // 전부 비슷하게 얕아 ①·④ 는 안 걸리고, 셋 다 60점 미만 — 가장 얕은 3회차(93°, 33점)를 지목
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 88.0), frame(2, 90.0), frame(3, 93.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회차").contains("얕았어요");
    }

    @Test
    @DisplayName("코멘트 ⑤: 모든 회차가 시범 깊이 근처(≥85점)면 회차 언급 없이 «지켰어요» 한 줄")
    void narrative_allGood_fallsBackToSingleLine() {
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 78.0), frame(2, 75.0), frame(3, 72.0)));

        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).contains("3회").contains("지켰어요");
    }

    @Test
    @DisplayName("코멘트 ⑤: 고르지만 시범 깊이에 못 미치면(60~84점) «지켰어요» 라고 하지 않는다 — 점수와 모순 방지")
    void narrative_evenButShort_doesNotClaimTargetKept() {
        // 82° 세 번 → 각 77점(GOOD). ①(낙차 없음)·③(60점 이상)·④(편차 0) 어느 것도 안 걸린다.
        SquatDepthAnalysisDto result = calculator.calculate(
                squatSession, List.of(frame(1, 82.0), frame(2, 82.0), frame(3, 82.0)));

        assertThat(result.getRepDepths()).extracting(RepDepthDto::getDepthScore).containsOnly(77);
        assertThat(result.getNarrative()).hasSize(1);
        assertThat(result.getNarrative().get(0)).doesNotContain("지켰어요").contains("3회 중 3회");
    }

    @Test
    @DisplayName("코멘트는 최대 4문장")
    void narrative_cappedAtFour() {
        // 편차가 크게 나도록 회차마다 깊이를 들쭉날쭉하게 구성 — 여러 트리거가 동시에 걸려도 4개 이하.
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(
                frame(1, 72.0), frame(2, 100.0), frame(3, 74.0), frame(4, 101.0), frame(5, 73.0)));

        assertThat(result.getNarrative()).hasSizeLessThanOrEqualTo(4);
    }

    // ── 미인정 시도 (V35, 2026-10-21) ─────────────────────────────────────

    private static RejectedAttemptProjection rejected(int attemptNo, String reason, Double minKnee, double timestampSec) {
        return new RejectedAttemptProjection(attemptNo, RejectedAttemptReason.valueOf(reason), 40.0, minKnee, 30.0, timestampSec);
    }

    private static final String DEPTH_SENTENCE =
            "2회는 바닥 자세가 확인되지 않아 인정되지 않았어요 — 무릎 76° 이하까지 앉아 잠깐 멈췄다 일어나세요.";

    @Test
    @DisplayName("미인정 시도는 같은 공식으로 채점돼 counted=false 행으로 시간순으로 섞이고, 세션 점수는 전체 평균이다")
    void rejected_scoredSameFormula_mergedByTime_sessionMeanOverAll() {
        // 목표 76.2° — 인정 76.0°→100, 81.2°→80 / 미인정 101.2°→0, 86.2°→60
        SquatDepthAnalysisDto result = calculator.calculate(squatSession,
                List.of(frame(1, 76.0), frame(2, 81.2)),
                List.of(rejected(1, "DEPTH_NOT_CONFIRMED", 101.2, 1.5),
                        rejected(2, "DEPTH_NOT_CONFIRMED", 86.2, 3.0)));

        assertThat(result.getRepDepths())
                .extracting(RepDepthDto::getRepNumber, RepDepthDto::getAttemptNumber, RepDepthDto::isCounted,
                        RepDepthDto::getDepthScore)
                .containsExactly(
                        tuple(1, null, true, 100),
                        tuple(null, 1, false, 0),
                        tuple(2, null, true, 80),
                        tuple(null, 2, false, 60));
        assertThat(result.getRepDepths().get(1).getMinKneeAngle()).isEqualTo(101.2);
        assertThat(result.getRepDepths().get(3).getTimeStamp()).isEqualTo("00:03");
        assertThat(result.getDepthScore()).isEqualTo(60); // (100+0+80+60)/4
        assertThat(result.getDepthGrade()).isEqualTo("FAIR");
        // 회차 지목은 인정 반복만(①: 1회차 100 → 2회차 80), 미인정은 사유별 한 문장으로 끝에
        assertThat(result.getNarrative()).containsExactly(
                "1회차 자세는 좋았는데, 2회차는 시범 깊이보다 조금 덜 앉았어요.",
                DEPTH_SENTENCE);
    }

    @Test
    @DisplayName("미인정 시도가 없으면 예전 계산과 똑같다")
    void noRejected_identicalToBefore() {
        List<PoseFrameProjection> frames = List.of(frame(1, 72.0), frame(2, 100.0), frame(3, 74.0));

        SquatDepthAnalysisDto before = calculator.calculate(squatSession, frames);
        SquatDepthAnalysisDto after = calculator.calculate(squatSession, frames, List.of());

        assertThat(after.getDepthScore()).isEqualTo(before.getDepthScore());
        assertThat(after.getNarrative()).isEqualTo(before.getNarrative());
        assertThat(after.getRepDepths()).extracting(RepDepthDto::getRepNumber).containsExactly(1, 2, 3);
        assertThat(after.getRepDepths()).allSatisfy(r -> assertThat(r.isCounted()).isTrue());
    }

    @Test
    @DisplayName("사유가 둘이면 두 문장 — 회차 코멘트 몫이 줄어 합계 4문장을 넘지 않는다")
    void bothReasons_twoSentences_totalCappedAtFour() {
        // 인정 반복만으로 ①(1→2 낙차)·③(3회차 0점)·④(편차 큼) 세 문장이 나오는 세션
        List<PoseFrameProjection> frames = List.of(frame(1, 76.0), frame(2, 95.0), frame(3, 110.0), frame(4, 76.0));
        assertThat(calculator.calculate(squatSession, frames).getNarrative()).hasSize(3);

        SquatDepthAnalysisDto result = calculator.calculate(squatSession, frames, List.of(
                rejected(1, "DEPTH_NOT_CONFIRMED", 100.0, 1.5),
                rejected(2, "SPLIT_STANCE", 98.0, 2.5),
                rejected(3, "DEPTH_NOT_CONFIRMED", 104.0, 3.5)));

        assertThat(result.getNarrative()).hasSize(4);
        assertThat(result.getNarrative().get(2)).isEqualTo(DEPTH_SENTENCE);
        assertThat(result.getNarrative().get(3))
                .isEqualTo("1회는 두 발이 앞뒤로 벌어진 자세로 보여 인정되지 않았어요 — 두 발을 어깨너비로 나란히 두고 앉으세요.");
    }

    @Test
    @DisplayName("인정 반복이 없고 미인정 시도만 있어도 리포트가 나온다 — 회차 코멘트 없이 미인정 문장만")
    void onlyRejected() {
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(),
                List.of(rejected(1, "DEPTH_NOT_CONFIRMED", 101.2, 2.0), rejected(2, "DEPTH_NOT_CONFIRMED", 96.2, 5.0)));

        assertThat(result).isNotNull();
        assertThat(result.getRepDepths()).extracting(RepDepthDto::getAttemptNumber).containsExactly(1, 2);
        assertThat(result.getDepthScore()).isEqualTo(10); // (0+20)/2
        assertThat(result.getNarrative()).containsExactly(DEPTH_SENTENCE);
    }

    @Test
    @DisplayName("무릎각이 미상인 시도는 깊이 행을 안 만들지만 «N회 인정되지 않았어요» 개수에는 든다")
    void unknownKnee_skippedFromRowsButCounted() {
        SquatDepthAnalysisDto result = calculator.calculate(squatSession, List.of(frame(1, 76.0)),
                List.of(rejected(1, "DEPTH_NOT_CONFIRMED", null, 1.5), rejected(2, "DEPTH_NOT_CONFIRMED", 86.2, 2.5)));

        assertThat(result.getRepDepths()).extracting(RepDepthDto::getAttemptNumber).containsExactly(null, 2);
        assertThat(result.getDepthScore()).isEqualTo(80); // (100+60)/2
        assertThat(result.getNarrative()).last().isEqualTo(DEPTH_SENTENCE);

        // 미인정 시도뿐인데 전부 미상이면 채점할 행이 없다 → null(예전 «유효 무릎각 없음» 과 같다)
        assertThat(calculator.calculate(squatSession, List.of(),
                List.of(rejected(1, "DEPTH_NOT_CONFIRMED", null, 1.5)))).isNull();
    }

    @Test
    @DisplayName("HALF 모드 — 미인정 시도도 HALF 공식, 문장은 목표 각도 없이 «목표 깊이»")
    void half_rejectedUsesHalfFormula() {
        Session halfSession = session(1L, SquatDepthMode.HALF);

        SquatDepthAnalysisDto result = calculator.calculate(halfSession, List.of(frame(1, 127.5)),
                List.of(rejected(1, "DEPTH_NOT_CONFIRMED", 115.0, 1.5)));

        assertThat(result.getRepDepths()).extracting(RepDepthDto::getDepthScore).containsExactly(100, 70);
        assertThat(result.getNarrative()).last()
                .isEqualTo("1회는 바닥 자세가 확인되지 않아 인정되지 않았어요 — 목표 깊이까지 앉아 잠깐 멈췄다 일어나세요.");
    }

    @Test
    @DisplayName("스쿼트가 아니면 미인정 시도가 있어도 null")
    void nonSquat_withRejected_null() {
        assertThat(calculator.calculate(session(2L, SquatDepthMode.FULL), List.of(),
                List.of(rejected(1, "DEPTH_NOT_CONFIRMED", 100.0, 1.0)))).isNull();
    }
}
