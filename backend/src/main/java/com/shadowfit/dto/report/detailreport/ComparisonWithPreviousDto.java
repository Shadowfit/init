package com.shadowfit.dto.report.detailreport;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ComparisonWithPreviousDto {
    private int syncRateDiff;       // 예: +5
    private int workoutMinutesDiff;  // 예: -2
    // 예: +30. 두 세션 중 하나라도 칼로리가 null(체중을 몰라 못 잼, 2026-10-21 CalorieEstimator)이면 null —
    // 없는 값을 0 으로 치면 «지난번 대비 −56» 같은 가짜 차이가 나온다.
    private Integer caloriesDiff;
}