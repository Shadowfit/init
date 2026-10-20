package com.shadowfit.global.util;

import com.shadowfit.model.exercise.SessionSet;

import java.util.List;

/**
 * 세트 요약 문자열("3세트 x 12회") 생성 — 리포트/주간/일별 응답이 같은 표기를 쓰도록 한 곳으로 모음.
 * 표기 규칙 출처는 {@code docs/decisions/report-aggregation.md} 결정 5.
 *
 * <p>과거 {@code ReportService}는 "1세트", {@code SessionService}는 "0세트"로 각자 리터럴을 들고 있어
 * 같은 세션이 화면마다 다르게 보이는 결함이 있었다(#69).
 *
 * <p>2026-09-22(V26) 부터 세트 표({@code exercise_session_sets})를 읽는다. 세트 경계가 «목표 도달» 하나뿐이라
 * <b>마지막 세트만 목표 미달일 수 있다</b> — 그래서 표기가 두 가지뿐이다:
 * <ul>
 *   <li>전부 같으면 {@code "3세트 x 12회"}</li>
 *   <li>마지막이 짧으면 {@code "3세트 x 12회 (마지막 7회)"} — «x 12회» 가 총 36회로 읽히지 않게</li>
 * </ul>
 * 세트 행이 없으면(세트 도입 전 세션, 또는 rep 이 없던 세션) 예전처럼 {@code "1세트 x N회"} 로 떨어진다.
 */
public class SetSummaryFormatter {
    private static final int FIXED_SET_COUNT = 1;

    private SetSummaryFormatter() {
    }

    /**
     * @param sets      세션의 세트 행, set_no 오름차순. 비어 있으면 {@code totalReps} 폴백
     * @param totalReps 총 반복 수. {@code exercise_sessions.total_reps}가 nullable이라 null이 올 수 있음
     *                  (nullable 컬럼 + JPA 로딩 경로). null이면 0회로 표기한다.
     */
    public static String format(List<SessionSet> sets, Integer totalReps) {
        if (sets == null || sets.isEmpty()) {
            return format(totalReps);
        }
        int perSet = sets.get(0).getReps();
        int last = sets.get(sets.size() - 1).getReps();
        String base = String.format("%d세트 x %d회", sets.size(), perSet);
        return sets.size() > 1 && last != perSet
                ? base + String.format(" (마지막 %d회)", last)
                : base;
    }

    /** 세트 행이 없는 세션의 폴백 — 세트 도입 전과 같은 표기. */
    public static String format(Integer totalReps) {
        int reps = totalReps == null ? 0 : totalReps;
        return String.format("%d세트 x %d회", FIXED_SET_COUNT, reps);
    }
}
