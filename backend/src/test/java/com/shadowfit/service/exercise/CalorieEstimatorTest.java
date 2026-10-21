package com.shadowfit.service.exercise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 칼로리 추정 (2026-10-21) — kcal = MET 3.0 × 3.5 × 체중 ÷ 200 × 분(소수), 소수 둘째 자리, 체중 모르면 null.
 */
@DisplayName("CalorieEstimator 테스트")
class CalorieEstimatorTest {

    private final CalorieEstimator estimator = new CalorieEstimator();
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 21, 9, 0, 0);

    @Test
    @DisplayName("70kg · 2분 45초 → 3.675 kcal/분 × 2.75 = 10.11 kcal (≈10.1)")
    void seventyKg_twoMinutesFortyFive() {
        assertThat(estimator.estimate(70.0, START, START.plusSeconds(165)))
                .isEqualByComparingTo("10.11");
    }

    @Test
    @DisplayName("분은 자르지 않고 소수로 쓴다 — 60kg · 10분 = 31.50, 10분 20초 = 32.55")
    void fractionalMinutes() {
        assertThat(estimator.estimate(60.0, START, START.plusMinutes(10))).isEqualByComparingTo("31.50");
        assertThat(estimator.estimate(60.0, START, START.plusMinutes(10).plusSeconds(20))).isEqualByComparingTo("32.55");
    }

    @Test
    @DisplayName("결과는 DECIMAL(7,2) 에 맞게 scale 2")
    void scaleTwo() {
        assertThat(estimator.estimate(70.0, START, START.plusSeconds(165)).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("체중을 모르거나 0 이하면 0 이 아니라 null")
    void unknownWeight_isNull() {
        assertThat(estimator.estimate(null, START, START.plusMinutes(5))).isNull();
        assertThat(estimator.estimate(0.0, START, START.plusMinutes(5))).isNull();
        assertThat(estimator.estimate(-70.0, START, START.plusMinutes(5))).isNull();
    }

    @Test
    @DisplayName("시각이 없으면 null, 종료가 시작보다 앞서면 0분으로 본다")
    void missingOrReversedTimes() {
        assertThat(estimator.estimate(70.0, null, START)).isNull();
        assertThat(estimator.estimate(70.0, START, null)).isNull();
        assertThat(estimator.estimate(70.0, START, START.minusMinutes(1))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("컬럼(DECIMAL(7,2))에 안 들어가는 값은 null — 완료 트랜잭션 롤백 방지(체중 오타 등)")
    void overflow_isNull() {
        // 9999.9kg(컬럼 최대 체중) × 525 kcal/분 → 200분이면 10만 kcal 를 넘는다
        assertThat(estimator.estimate(9999.9, START, START.plusMinutes(200))).isNull();
    }
}
