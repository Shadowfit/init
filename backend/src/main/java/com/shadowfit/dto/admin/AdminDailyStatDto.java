package com.shadowfit.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "관리자 대시보드 — 하루치 추이")
public record AdminDailyStatDto(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
        @Schema(description = "그날 시작된 세션 수") long sessionCount,
        @Schema(description = "그중 완료") long completedCount,
        @Schema(description = "완료 세션 평균 싱크로율 (없으면 null)") Double averageSyncRate,
        @Schema(description = "그날 가입한 회원 수") long newMemberCount
) {}
