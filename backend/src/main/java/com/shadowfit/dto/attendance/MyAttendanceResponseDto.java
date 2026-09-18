package com.shadowfit.dto.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 메인 화면 스트릭 카드 (streak-card-api.md §3, 2026-09-18 confirm). 서버는 값만 준다 — «오늘 운동하면 6일째»,
 * «최고 기록 갱신 중» 같은 문구는 프론트가 파생한다({@code MemberAttendanceStatusDto} 와 같은 관례).
 * «갱신 중» = {@code currentStreak == longestStreak && longestStreakEnd} 가 오늘 또는 어제.
 *
 * <p>출석 정의는 한 곳({@code AttendanceService}) — COMPLETED 세션이 있는 날, 서버 LocalDate 기준.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "내 출석·스트릭 카드 res dto")
public class MyAttendanceResponseDto {
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "서버 기준 오늘(Asia/Seoul) — 기기 시계와 어긋날 때 «오늘»이 어느 날인지", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate today;

    @Schema(description = "오늘 COMPLETED 세션이 있는가", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean attendedToday;

    @Schema(description = "현재 연속 출석 일수 — 오늘 또는 어제까지 이어진 것. 없으면 0", requiredMode = Schema.RequiredMode.REQUIRED)
    private int currentStreak;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "현재 연속 구간의 첫날. currentStreak 이 0 이면 null")
    private LocalDate currentStreakStart;

    @Schema(description = "전 기간 최장 연속 출석 일수. 기록 없으면 0", requiredMode = Schema.RequiredMode.REQUIRED)
    private int longestStreak;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "최장 구간의 첫날 — 동률이면 가장 최근 구간. longestStreak 이 0 이면 null")
    private LocalDate longestStreakStart;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "최장 구간의 마지막 날. longestStreak 이 0 이면 null")
    private LocalDate longestStreakEnd;

    @Schema(description = "이번 주 월~일 7개 고정 — 오늘 이후의 날은 attended=false", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Day> thisWeek;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @Schema(description = "하루")
    public static class Day {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "날짜", requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate date;

        @Schema(description = "그날 COMPLETED 세션이 있는가", requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean attended;
    }
}
