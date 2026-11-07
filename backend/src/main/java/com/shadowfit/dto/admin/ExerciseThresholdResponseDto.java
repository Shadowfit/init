package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Exercise;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 판정 기준 변경 응답 — {@code {"exerciseId":1,"syncGoodThreshold":80.0,"syncWarnThreshold":60.0}}.
 * 숫자를 BigDecimal(80.00) 이 아니라 double 로 내는 이유는 세션 시작·리포트 응답과 같은 모양을 쓰려는 것이다.
 */
@Schema(description = "싱크로율 판정 기준 변경 결과")
public record ExerciseThresholdResponseDto(
        @Schema(description = "운동 ID", example = "1") Long exerciseId,
        @Schema(description = "정석 기준(이 점수 이상 = 정석)", example = "80.0") double syncGoodThreshold,
        @Schema(description = "위험 기준(이 점수 이상 = 교정 필요, 미만 = 위험)", example = "60.0") double syncWarnThreshold
) {
    public static ExerciseThresholdResponseDto fromEntity(Exercise e) {
        return new ExerciseThresholdResponseDto(
                e.getId(),
                e.getSyncGoodThreshold().doubleValue(),
                e.getSyncWarnThreshold().doubleValue()
        );
    }
}