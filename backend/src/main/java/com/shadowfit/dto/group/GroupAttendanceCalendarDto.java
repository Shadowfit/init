package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 모임 출석 캘린더 (social-cheer-and-group-feed.md §3-E). 서버는 날짜별 출석 인원과 현재 ACTIVE 멤버 수만
 * 준다 — 칸 농도(인원 비율 / 전원 여부 / 나 기준)는 프론트 매핑이고, 레퍼런스 화면의 농도 의미는 추정이라
 * 서버가 한 해석을 고정하지 않는다. 그 달의 모든 날을 싣는다(출석 0 인 날 포함) — 프론트가 빈 칸을 따로
 * 만들지 않게.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "모임 출석 캘린더 res dto")
public class GroupAttendanceCalendarDto {
    @Schema(description = "연도", requiredMode = Schema.RequiredMode.REQUIRED)
    private int year;

    @Schema(description = "월 (1~12)", requiredMode = Schema.RequiredMode.REQUIRED)
    private int month;

    @Schema(description = "현재 ACTIVE 멤버 수 — 농도의 분모 (§3-E: 시점 멤버가 아니라 현재)", requiredMode = Schema.RequiredMode.REQUIRED)
    private int activeMemberCount;

    @Schema(description = "그 달의 모든 날, 1일부터", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Day> days;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @Schema(description = "하루")
    public static class Day {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "날짜", requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate date;

        @Schema(description = "그날 COMPLETED 세션이 있는 현재 ACTIVE 멤버 수", requiredMode = Schema.RequiredMode.REQUIRED)
        private int attendedCount;
    }
}
