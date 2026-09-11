package com.shadowfit.service.group;

import com.shadowfit.dto.group.MemberAttendanceStatusDto;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MemberAttendanceStatusService 테스트")
class MemberAttendanceStatusServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private AttendanceService attendanceService;

    private MemberAttendanceStatusService service;
    private Group group;
    private Member me, done, alive, none;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MemberAttendanceStatusService(groupRepository, groupMemberRepository, attendanceService);
        me = member(1L, "me");
        done = member(2L, "zed");    // 오늘 완료 — 이름이 뒤여도 맨 위
        alive = member(3L, "amy");   // 오늘 안 함, streak 8
        none = member(4L, "bob");    // 기록 없음
        group = Group.builder().id(10L).name("그룹").inviteCode("TESTCD01").createdBy(me).build();
        when(groupRepository.existsById(10L)).thenReturn(true);
    }

    @Test
    @DisplayName("groupMemberStatuses — 오늘 완료 → 진행 중 → 없음 순, 동률은 username (레퍼런스 순서)")
    void groupMemberStatuses_referenceOrder() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(10L, 1L, GroupMemberStatus.ACTIVE)).thenReturn(true);
        when(groupMemberRepository.findAllWithMemberByGroupIdAndStatus(10L, GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership(none), membership(alive), membership(done), membership(me)));
        when(attendanceService.attendedOn(anyCollection(), eq(TODAY))).thenReturn(Set.of(2L, 1L));
        when(attendanceService.currentStreak(2L, TODAY)).thenReturn(3);
        when(attendanceService.currentStreak(1L, TODAY)).thenReturn(3);
        when(attendanceService.currentStreak(3L, TODAY)).thenReturn(8);
        when(attendanceService.currentStreak(4L, TODAY)).thenReturn(0);

        List<MemberAttendanceStatusDto> result = service.groupMemberStatuses(10L, 1L, TODAY);

        // done(3, 오늘) 과 me(3, 오늘) 는 동률 → username: "me" < "zed"
        assertThat(result).extracting(MemberAttendanceStatusDto::getUsername)
                .containsExactly("me", "zed", "amy", "bob");
        assertThat(result.get(2).isAttendedToday()).isFalse();
        assertThat(result.get(2).getStreak()).isEqualTo(8);
        assertThat(result.get(3).getStreak()).isZero();
    }

    @Test
    @DisplayName("groupMemberStatuses — 요청자가 ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER, 출석은 조회조차 안 한다")
    void groupMemberStatuses_outsiderForbidden() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(10L, 99L, GroupMemberStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> service.groupMemberStatuses(10L, 99L, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
        verify(attendanceService, never()).attendedOn(anyCollection(), any());
    }

    @Test
    @DisplayName("groupMemberStatuses — 없는 그룹이면 GROUP_NOT_FOUND")
    void groupMemberStatuses_unknownGroup() {
        when(groupRepository.existsById(77L)).thenReturn(false);

        assertThatThrownBy(() -> service.groupMemberStatuses(77L, 1L, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GROUP_NOT_FOUND);
    }

    @Test
    @DisplayName("friendStatuses — 내 모임들의 멤버 합집합, 나는 빠지고 두 모임에 겹친 사람은 한 번만")
    void friendStatuses_unionWithoutSelfAndDuplicates() {
        Group other = Group.builder().id(11L).name("다른 그룹").inviteCode("TESTCD02").createdBy(me).build();
        when(groupMemberRepository.findAllByMemberIdAndStatus(1L, GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership(group, me), membership(other, me)));
        when(groupMemberRepository.findAllWithMemberByGroupIdInAndStatus(List.of(10L, 11L), GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership(group, me), membership(group, done), membership(group, alive),
                                    membership(other, me), membership(other, alive), membership(other, none)));
        when(attendanceService.attendedOn(anyCollection(), eq(TODAY))).thenReturn(Set.of(2L));
        when(attendanceService.currentStreak(any(), eq(TODAY))).thenReturn(0);

        List<MemberAttendanceStatusDto> result = service.friendStatuses(1L, TODAY);

        assertThat(result).extracting(MemberAttendanceStatusDto::getMemberId).containsExactly(2L, 3L, 4L);
        assertThat(result).extracting(MemberAttendanceStatusDto::getUsername).doesNotContain("me");
    }

    @Test
    @DisplayName("friendStatuses — 모임이 없으면 빈 목록, 출석 조회 없음")
    void friendStatuses_noGroups() {
        when(groupMemberRepository.findAllByMemberIdAndStatus(1L, GroupMemberStatus.ACTIVE)).thenReturn(List.of());

        assertThat(service.friendStatuses(1L, TODAY)).isEmpty();
        verify(attendanceService, never()).attendedOn(anyCollection(), any());
    }

    private GroupMember membership(Member m) { return membership(group, m); }

    private GroupMember membership(Group g, Member m) {
        return GroupMember.builder().group(g).member(m).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build();
    }

    private static Member member(Long id, String username) {
        return Member.builder().id(id).email(username + "@test.com").username(username)
                .password("encoded").role(UserRole.USER).build();
    }
}
