package com.shadowfit.controller;

import com.shadowfit.dto.attendance.MyAttendanceResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.exercise.MyAttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 내 출석 — 메인 화면 스트릭 카드. 경로가 /streaks 가 아니라 /attendance 인 이유는 도메인 이름이 «출석»
 * ({@code AttendanceService}, {@code /groups/{id}/attendance})이고 응답에 streak 아닌 항목(오늘 여부·이번 주)도
 * 있어서(streak-card-api.md §5). «내 것» 은 {@code /groups/mine}·{@code /invitations/mine} 관례.
 */
@Tag(name = "출석", description = "내 출석·스트릭 카드")
@RestController
@RequestMapping("/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final MyAttendanceService myAttendanceService;

    @Operation(summary = "내 스트릭 카드",
            description = "오늘 완료 여부·현재 연속(시작일)·전 기간 최장 연속(구간)·이번 주 월~일 7칸. "
                    + "출석 = 그날 COMPLETED 세션 1건 이상(서버 날짜 기준). 문구(«오늘 하면 N일째», «갱신 중»)는 프론트 파생.")
    @GetMapping("/mine")
    public ResponseEntity<MyAttendanceResponseDto> getMyAttendance(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(myAttendanceService.myAttendance(userDetails.getMember().getId(), LocalDate.now()));
    }
}
