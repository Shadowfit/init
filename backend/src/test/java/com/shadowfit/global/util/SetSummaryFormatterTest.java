package com.shadowfit.global.util;

import com.shadowfit.model.exercise.SessionSet;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세트 요약 표기 단위 테스트 — #69 회귀 방지.
 *
 * <p>리포트(ReportService)와 주간/일별(SessionService)이 각자 리터럴을 들고 있다가
 * "1세트"/"0세트"로 갈렸던 결함이 있었다. 두 호출부가 이 포매터 하나만 쓰도록 모았으므로
 * 표기 규칙 검증도 여기 한 곳에서 한다.
 */
class SetSummaryFormatterTest {

    @Test
    @DisplayName("세트 수는 1로 고정된다 — BE-09 전까지 세트 개념이 스키마에 없음")
    void 세트수는_1로_고정() {
        assertThat(SetSummaryFormatter.format(12)).isEqualTo("1세트 x 12회");
    }

    @Test
    @DisplayName("totalReps가 null이면 0회로 표기한다 — total_reps 컬럼이 nullable")
    void null이면_0회() {
        assertThat(SetSummaryFormatter.format(null)).isEqualTo("1세트 x 0회");
    }

    @Test
    @DisplayName("반복 0회도 그대로 표기된다")
    void 반복_0회() {
        assertThat(SetSummaryFormatter.format(0)).isEqualTo("1세트 x 0회");
    }

    // ─── 세트 표(V26) 기반 ─────────────────────────────────────────────────────

    private static SessionSet set(int setNo, int reps) {
        return SessionSet.builder().setNo(setNo).reps(reps)
                .avgSyncRate(java.math.BigDecimal.TEN).startedSec(0.0).endedSec(1.0).build();
    }

    @Test
    @DisplayName("세트가 전부 목표를 채우면 «N세트 x T회»")
    void sets_allFull() {
        assertThat(SetSummaryFormatter.format(List.of(set(1, 12), set(2, 12), set(3, 12)), 36))
                .isEqualTo("3세트 x 12회");
    }

    @Test
    @DisplayName("마지막 세트만 짧으면 «(마지막 r회)» 를 붙인다 — x 12회 가 총 36회로 읽히지 않게")
    void sets_lastShort() {
        assertThat(SetSummaryFormatter.format(List.of(set(1, 12), set(2, 12), set(3, 7)), 31))
                .isEqualTo("3세트 x 12회 (마지막 7회)");
    }

    @Test
    @DisplayName("세트가 하나뿐이면 미달이어도 그 횟수 그대로")
    void sets_single() {
        assertThat(SetSummaryFormatter.format(List.of(set(1, 7)), 7)).isEqualTo("1세트 x 7회");
    }

    @Test
    @DisplayName("세트 행이 없으면 totalReps 폴백(세트 도입 전 세션)")
    void sets_empty_fallsBack() {
        assertThat(SetSummaryFormatter.format(List.of(), 12)).isEqualTo("1세트 x 12회");
        assertThat(SetSummaryFormatter.format(null, null)).isEqualTo("1세트 x 0회");
    }
}
