package com.shadowfit.controller;

import com.shadowfit.dto.report.CalendarMainResponseDto;
import com.shadowfit.dto.report.DailyLogRequestDto;
import com.shadowfit.dto.report.WeeklyActivityResponseDto;
import com.shadowfit.service.Exercise.SessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "운동 활동 관리", description = "메인페이지 운동 활동 관리")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Slf4j
public class ExerciseRecordController {
    private final SessionService sessionService;
    private final DailyLogService dailyLogService;

    @GetMapping("/weekly-summary")
    public ResponseEntity<WeeklyActivityResponseDto> getWeeklySummary(@AuthenticationPrincipal Long memberId) {
        // 서비스 로직에서 주간 통계 및 오늘 운동 리스트를 계산해서 반환
        WeeklyActivityResponseDto response = sessionService.getWeeklyActivity(memberId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/calendar")
    public ResponseEntity<CalendarMainResponseDto> getCalendarRecords(
            @AuthenticationPrincipal Long memberId,
            @RequestParam int year,
            @RequestParam int month) {

        CalendarMainResponseDto response = sessionService.getCalendarMain(memberId, year, month);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/daily-logs")
    public ResponseEntity<Void> saveDailyLog(
            @AuthenticationPrincipal Long memberId,
            @RequestBody DailyLogRequestDto request) {

        dailyLogService.saveOrUpdateLog(memberId, request);
        return ResponseEntity.noContent().build();
    }




}
