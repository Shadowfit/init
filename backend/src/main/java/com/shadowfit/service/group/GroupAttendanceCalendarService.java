package com.shadowfit.service.group;

import com.shadowfit.dto.group.GroupAttendanceCalendarDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.service.exercise.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 모임 출석 캘린더 — 모임 상세 화면의 달력 (social-cheer-and-group-feed.md §3-E, 2026-09-11 confirm).
 *
 * <p><b>분모·분자 모두 현재 ACTIVE 멤버.</b> 농도 = 그날 COMPLETED 세션이 있는 현재 ACTIVE 멤버 수 /
 * 현재 ACTIVE 멤버 수. LEFT 멤버의 과거 출석은 안 센다(IN 리스트에 ACTIVE 만). «지금 모임 사람들이
 * 그날 몇 명 했나» 하나로 정의가 닫힌다. 시점 멤버 수(그날 멤버였던 사람)는 {@code left_at} 이력이
 * 없어 지금 못 구하고, 그 정확도를 요구하는 화면도 없어 택하지 않았다.
 *
 * <p>권한은 §3-G — 같은 ACTIVE 모임 멤버만. 개인이 아니라 인원수로 합산되므로 개인별 날짜는 식별되지
 * 않는다.
 *
 * <p>사전집계·캐시 없음 — 읽는 행 ≤ 인원 × 그 달의 완료 세션수라 근거가 없다
 * ({@code weekly-monthly-stat-preaggregation.md} §1 과 같은 판단).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupAttendanceCalendarService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final AttendanceService attendanceService;

    public GroupAttendanceCalendarDto monthlyCalendar(Long groupId, Long requesterId, int year, int month) {
        if (month < 1 || month > 12) {
            // LocalDate.of 가 DateTimeException 으로 500 을 내기 전에 400 으로 막는다.
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (!groupRepository.existsById(groupId)) {
            throw new BusinessException(ErrorCode.GROUP_NOT_FOUND);
        }
        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, requesterId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }

        List<Long> activeMemberIds = groupMemberRepository
                .findAllByGroupIdAndStatus(groupId, GroupMemberStatus.ACTIVE).stream()
                .map(gm -> gm.getMember().getId())
                .toList();

        YearMonth ym = YearMonth.of(year, month);
        Map<LocalDate, Integer> counts = attendanceService.attendeeCountsByDay(
                activeMemberIds, ym.atDay(1), ym.atEndOfMonth());

        List<GroupAttendanceCalendarDto.Day> days = IntStream.rangeClosed(1, ym.lengthOfMonth())
                .mapToObj(ym::atDay)
                .map(d -> new GroupAttendanceCalendarDto.Day(d, counts.getOrDefault(d, 0)))
                .toList();

        return GroupAttendanceCalendarDto.builder()
                .year(year).month(month)
                .activeMemberCount(activeMemberIds.size())
                .days(days)
                .build();
    }
}
