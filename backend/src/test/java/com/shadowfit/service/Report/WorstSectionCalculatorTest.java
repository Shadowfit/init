package com.shadowfit.service.Report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorstSectionCalculator 단위 테스트 — 읽기(ReportService)·쓰기(SessionService.applyComplete,
 * precompute-on-write) 양쪽이 공유하는 순수 계산 로직(report-read-path.md §9-1).
 */
@DisplayName("WorstSectionCalculator 테스트")
class WorstSectionCalculatorTest {

    private WorstSectionCalculator calculator;
    private Session session;

    @BeforeEach
    void setUp() {
        calculator = new WorstSectionCalculator();

        Exercise exercise = Exercise.builder()
                .id(1L)
                .name("스쿼트")
                .category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build();

        session = Session.builder()
                .id(1L)
                .exercise(exercise)
                .startTime(LocalDateTime.now())
                .status(Status.COMPLETED)
                .build();
    }

    private PoseFrameProjection frame(double timestampSec, Double syncRate, String feedbackMessage) {
        return new PoseFrameProjection(timestampSec, syncRate, feedbackMessage);
    }

    @Test
    @DisplayName("poseFrames가 null이면 null 반환")
    void nullFrames_returnsNull() {
        assertThat(calculator.calculate(session, null)).isNull();
    }

    @Test
    @DisplayName("poseFrames가 WORST_WINDOW_SIZE(3)보다 적으면 null 반환")
    void tooFewFrames_returnsNull() {
        List<PoseFrameProjection> frames = List.of(
                frame(0.0, 80.0, null),
                frame(0.1, 75.0, null)
        );
        assertThat(calculator.calculate(session, frames)).isNull();
    }

    @Test
    @DisplayName("연속 3프레임 평균이 가장 낮은 구간을 worst로 선정")
    void selectsLowestAverageWindow() {
        // 앞 3프레임(90,88,92 → 평균 90)보다 뒤 3프레임(40,42,38 → 평균 40)이 낮음 → worst
        List<PoseFrameProjection> frames = List.of(
                frame(0.0, 90.0, null),
                frame(0.1, 88.0, null),
                frame(0.2, 92.0, null),
                frame(0.3, 40.0, "KNEE_OUT"),
                frame(0.4, 42.0, "KNEE_OUT"),
                frame(0.5, 38.0, "KNEE_OUT")
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getExerciseName()).isEqualTo("스쿼트");
        assertThat(result.getReason()).contains("싱크로율 40%").contains("KNEE_OUT");
    }

    @Test
    @DisplayName("구간 내 syncRate가 null이면 해당 구간은 worst 후보에서 배제(무한대 취급)")
    void nullSyncRateWindowIsExcluded() {
        // 중간 구간에 null이 끼어 있어 그 구간 평균은 사실상 무한대 취급 → 명확히 낮은 뒤쪽 구간이 선택돼야 함
        List<PoseFrameProjection> frames = List.of(
                frame(0.0, 95.0, null),
                frame(0.1, null, null),
                frame(0.2, 95.0, null),
                frame(0.3, 50.0, "HIP_HIGH"),
                frame(0.4, 50.0, "HIP_HIGH"),
                frame(0.5, 50.0, "HIP_HIGH")
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getReason()).contains("싱크로율 50%").contains("HIP_HIGH");
    }

    @Test
    @DisplayName("모든 3프레임 구간에 null syncRate가 껴있으면(유효 구간 없음) null 반환")
    void allWindowsHaveNullSyncRate_returnsNull() {
        // 3프레임뿐이고 유일한 구간에 null이 껴있음 → 평균 낼 유효 구간이 아예 없음
        List<PoseFrameProjection> frames = List.of(
                frame(0.0, null, null),
                frame(0.1, 80.0, null),
                frame(0.2, 80.0, null)
        );

        assertThat(calculator.calculate(session, frames)).isNull();
    }

    @Test
    @DisplayName("worst 구간 안 feedback_message가 전부 비어있으면 reason에 싱크로율만 표기")
    void blankFeedback_reasonHasOnlySyncRate() {
        List<PoseFrameProjection> frames = List.of(
                frame(0.0, 60.0, ""),
                frame(0.1, 60.0, null),
                frame(0.2, 60.0, "  ")
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getReason()).isEqualTo("싱크로율 60%");
    }

    @Test
    @DisplayName("worst 구간 안 가장 자주 등장한 feedback_message를 reason에 포함")
    void dominantFeedback_pickedByFrequency() {
        List<PoseFrameProjection> frames = Arrays.asList(
                frame(0.0, 50.0, "KNEE_OUT"),
                frame(0.1, 50.0, "KNEE_OUT"),
                frame(0.2, 50.0, "BACK_BENT")
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getReason()).contains("KNEE_OUT");
        assertThat(result.getReason()).doesNotContain("BACK_BENT");
    }

    @Test
    @DisplayName("대표 timestamp는 구간 중앙 프레임 기준 mm:ss 포맷")
    void representativeTimestamp_isFormattedMmSs() {
        List<PoseFrameProjection> frames = List.of(
                frame(70.0, 50.0, null),  // 1:10
                frame(75.0, 50.0, null),  // 중앙(대표) → 1:15
                frame(80.0, 50.0, null)   // 1:20
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getTimeStamp()).isEqualTo("01:15");
    }
}
