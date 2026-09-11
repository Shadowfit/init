package com.shadowfit.service.group;

import com.shadowfit.dto.group.GroupAttendanceCalendarDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.service.exercise.AttendanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GroupAttendanceCalendarService 테스트")
class GroupAttendanceCalendarServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private AttendanceService attendanceService;

    private GroupAttendanceCalendarService service;
    private Group group;
    private Member a, b, c;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupAttendanceCalendarService(groupRepository, groupMemberRepository, attendanceService);
        a = member(1L, "a"); b = member(2L, "b"); c = member(3L, "c");
        group = Group.builder().id(10L).name("그룹").inviteCode("TESTCD01").createdBy(a).build();
        when(groupRepository.existsById(10L)).thenReturn(true);
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(10L, 1L, GroupMemberStatus.ACTIVE)).thenReturn(true);
    }

    @Test
    @DisplayName("그 달의 모든 날을 1일부터 채우고, 출석 없는 날은 0, 분모는 현재 ACTIVE 멤버 수")
    void monthlyCalendar_fillsEveryDayWithZeroDefault() {
        when(groupMemberRepository.findAllByGroupIdAndStatus(10L, GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership(a), membership(b), membership(c)));
        when(attendanceService.attendeeCountsByDay(eq(List.of(1L, 2L, 3L)), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 2, 28))))
                .thenReturn(Map.of(LocalDate.of(2026, 2, 3), 3, LocalDate.of(2026, 2, 14), 1));

        GroupAttendanceCalendarDto result = service.monthlyCalendar(10L, 1L, 2026, 2);

        assertThat(result.getActiveMemberCount()).isEqualTo(3);
        assertThat(result.getDays()).hasSize(28); // 2026-02 는 28일
        assertThat(result.getDays().get(0).getDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(result.getDays().get(2).getAttendedCount()).isEqualTo(3);
        assertThat(result.getDays().get(13).getAttendedCount()).isEqualTo(1);
        assertThat(result.getDays().stream().filter(d -> d.getAttendedCount() == 0).count()).isEqualTo(26);
    }

    @Test
    @DisplayName("ACTIVE 멤버만 분모·분자에 들어간다 — 조회에 넘기는 id 가 ACTIVE 목록 그대로")
    void monthlyCalendar_onlyActiveMembersCounted() {
        // 레포지토리가 ACTIVE 만 돌려준다는 계약 위에서, 서비스는 그 id 들만 출석 조회에 넘긴다.
        when(groupMemberRepository.findAllByGroupIdAndStatus(10L, GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership(a), membership(c)));
        when(attendanceService.attendeeCountsByDay(anyCollection(), any(), any())).thenReturn(Map.of());

        GroupAttendanceCalendarDto result = service.monthlyCalendar(10L, 1L, 2026, 9);

        assertThat(result.getActiveMemberCount()).isEqualTo(2);
        verify(attendanceService).attendeeCountsByDay(eq(List.of(1L, 3L)), eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)));
    }

    @Test
    @DisplayName("month 가 1~12 밖이면 INVALID_INPUT_VALUE(400) — DateTimeException 500 이 아니라")
    void monthlyCalendar_invalidMonth() {
        assertThatThrownBy(() -> service.monthlyCalendar(10L, 1L, 2026, 13))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verify(attendanceService, never()).attendeeCountsByDay(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("요청자가 ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER")
    void monthlyCalendar_outsiderForbidden() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(10L, 99L, GroupMemberStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> service.monthlyCalendar(10L, 99L, 2026, 9))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    private GroupMember membership(Member m) {
        return GroupMember.builder().group(group).member(m).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build();
    }

    private static Member member(Long id, String username) {
        return Member.builder().id(id).email(username + "@test.com").username(username)
                .password("encoded").role(UserRole.USER).build();
    }
}
