package com.shadowfit.service.exercise;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionSet;
import com.shadowfit.repository.exercise.PoseDataRepository.RepSummaryProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rep 목록 → 세트 목록 (순수 함수). DB 경로는 {@code SessionSyncStatsTest} 의 세트 테스트가 맡는다.
 * 여기서 고정하는 것은 «세트 번호는 rep 번호의 함수」 — 빠진 rep 이 있어도 뒤 rep 이 앞 세트로 당겨지지 않는다.
 */
class SessionSetAssemblerTest {

    private static RepSummaryProjection rep(int n, double sync, double from, double to) {
        return new RepSummaryProjection() {
            public Integer getRepNumber() { return n; }
            public Double getAvgSyncRate() { return sync; }
            public Double getStartedSec() { return from; }
            public Double getEndedSec() { return to; }
        };
    }

    private static Session sessionWithTarget(Integer t) {
        return Session.builder().targetRepsPerSet(t).build();
    }

    @Test
    @DisplayName("rep 번호에 빈틈이 있어도 세트는 rep 번호로 정해진다 — rep 3 이 없으면 2세트는 rep 4 하나")
    void gap_keepsSetByRepNumber() {
        List<SessionSet> sets = SessionSetAssembler.assemble(sessionWithTarget(3),
                List.of(rep(1, 50, 0, 1), rep(2, 70, 2, 3), rep(4, 90, 6, 7), rep(5, 90, 8, 9)));

        assertThat(sets).extracting(SessionSet::getSetNo).containsExactly(1, 2);
        assertThat(sets).extracting(SessionSet::getReps).containsExactly(2, 2);
        assertThat(sets.get(1).getStartedSec()).isEqualTo(6.0);
        assertThat(sets.get(1).getEndedSec()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("target 이 없거나 rep 이 없으면 빈 목록")
    void noTarget_orNoReps_empty() {
        assertThat(SessionSetAssembler.assemble(sessionWithTarget(null), List.of(rep(1, 50, 0, 1)))).isEmpty();
        assertThat(SessionSetAssembler.assemble(sessionWithTarget(5), List.of())).isEmpty();
    }

    @Test
    @DisplayName("평균은 소수 둘째 자리 반올림")
    void average_scale2() {
        List<SessionSet> sets = SessionSetAssembler.assemble(sessionWithTarget(3),
                List.of(rep(1, 50, 0, 1), rep(2, 60, 2, 3), rep(3, 61, 4, 5)));

        assertThat(sets.get(0).getAvgSyncRate()).isEqualByComparingTo("57.00");
    }
}
