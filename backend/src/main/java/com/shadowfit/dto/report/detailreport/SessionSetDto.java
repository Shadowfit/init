package com.shadowfit.dto.report.detailreport;

import com.shadowfit.model.exercise.SessionSet;
import io.swagger.v3.oas.annotations.media.Schema;

/** 세션 리포트의 세트 한 줄 (V26, {@code exercise_session_sets}). */
@Schema(description = "세트별 요약")
public record SessionSetDto(
        @Schema(description = "세트 번호(1-based)", example = "2") int setNo,
        @Schema(description = "이 세트의 횟수. 마지막 세트만 목표 미달일 수 있다", example = "12") int reps,
        @Schema(description = "rep 가중 평균 싱크로율", example = "78.5") double avgSyncRate,
        @Schema(description = "세트 시작 — 첫 프레임 기준 경과 초", example = "12.3") double startedSec,
        @Schema(description = "세트 끝 — 첫 프레임 기준 경과 초", example = "58.9") double endedSec
) {
    public static SessionSetDto from(SessionSet s) {
        return new SessionSetDto(s.getSetNo(), s.getReps(), s.getAvgSyncRate().doubleValue(),
                s.getStartedSec(), s.getEndedSec());
    }
}
