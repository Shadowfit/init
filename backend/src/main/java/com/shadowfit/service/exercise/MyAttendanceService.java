package com.shadowfit.service.exercise;

import com.shadowfit.dto.attendance.MyAttendanceResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 메인 화면 스트릭 카드 — {@code AttendanceService} 의 계산기 셋(현재 구간·최장 구간·기간 출석일)을 한 응답으로
 * 모은다(streak-card-api.md, 2026-09-18 confirm). 요청당 쿼리 3개(현재 streak 페이지 1~n·이번 주·전 기간
 * DISTINCT), 전부 {@code idx_session_member_status_start} 한 인덱스.
 *
 * <p>{@code /reports/calendar} 의 {@code consecutiveDays} 와 별도 API 인 이유는 같은 문서 §2 — 달력은
 * {@code year/month} 파라미터 API 라 파라미터와 무관한 값(최장·이번 주)이 어울리지 않고, 두 값은 같은 계산기라
 * 어긋날 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyAttendanceService {

    private final AttendanceService attendanceService;

    public MyAttendanceResponseDto myAttendance(Long memberId, LocalDate today) {
        AttendanceService.StreakRun current = attendanceService.currentStreakRun(memberId, today);
        AttendanceService.StreakRun longest = attendanceService.longestStreakRun(memberId);

        // 월~일 7칸 고정 — 미래 날을 빼고 주면 요일마다 칸 수가 달라져 프론트가 채워야 한다(§3).
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Set<LocalDate> attended = attendanceService.attendedDays(memberId, monday, today);
        List<MyAttendanceResponseDto.Day> week = IntStream.range(0, 7)
                .mapToObj(monday::plusDays)
                .map(d -> MyAttendanceResponseDto.Day.builder().date(d).attended(attended.contains(d)).build())
                .toList();

        return MyAttendanceResponseDto.builder()
                .today(today)
                .attendedToday(attended.contains(today)) // 이번 주 조회에 오늘이 들어 있어 exists 쿼리가 따로 없다
                .currentStreak(current.length())
                .currentStreakStart(current.start())
                .longestStreak(longest.length())
                .longestStreakStart(longest.start())
                .longestStreakEnd(longest.end())
                .thisWeek(week)
                .build();
    }
}
