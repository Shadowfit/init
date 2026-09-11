package com.shadowfit.service.group;

import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.GroupDetailResponseDto;
import com.shadowfit.dto.group.GroupResponseDto;
import com.shadowfit.dto.group.InviteCodeResponseDto;
import com.shadowfit.dto.group.JoinGroupRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.repository.group.GroupInvitationRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GroupService 테스트")
class GroupServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long MEMBER_ID = 10L;

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private InviteCodeGenerator inviteCodeGenerator;
    @Mock private GroupInvitationRepository groupInvitationRepository;
    @Mock private GroupEventService groupEventService;

    private GroupService groupService;
    private Member creator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        groupService = new GroupService(groupRepository, groupMemberRepository, memberRepository, inviteCodeGenerator,
                groupInvitationRepository, groupEventService, new ObjectMapper());
        creator = newMember(MEMBER_ID, "creator");
        // 기본: 첫 번째로 뽑은 코드가 곧 통과한다. 충돌 시나리오는 개별 테스트에서 덮어쓴다.
        when(inviteCodeGenerator.generate()).thenReturn("FRESHCD1");
        when(groupRepository.existsByInviteCode(any())).thenReturn(false);
    }

    @Test
    @DisplayName("createGroup — 생성자를 OWNER·ACTIVE로 자동 가입시킨다")
    void createGroup_addsCreatorAsOwner() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(creator));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupResponseDto response = groupService.createGroup(MEMBER_ID, new CreateGroupRequestDto("그룹1"));

        assertThat(response.getName()).isEqualTo("그룹1");
        assertThat(response.getCreatedById()).isEqualTo(MEMBER_ID);
        assertThat(response.getInviteCode()).isEqualTo("FRESHCD1");

        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(groupMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(GroupRole.OWNER);
        assertThat(captor.getValue().getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("createGroup — 존재하지 않는 사용자면 USER_NOT_FOUND")
    void createGroup_unknownCreator_throws() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.createGroup(MEMBER_ID, new CreateGroupRequestDto("그룹1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("getGroupDetail — 그룹이 없으면 GROUP_NOT_FOUND")
    void getGroupDetail_unknownGroup_throws() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.getGroupDetail(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GROUP_NOT_FOUND);
    }

    @Test
    @DisplayName("getGroupDetail — ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER (그룹은 존재해도)")
    void getGroupDetail_notActiveMember_throws() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(newGroup(creator)));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> groupService.getGroupDetail(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("getGroupDetail — ACTIVE 멤버면 멤버 목록을 포함해 반환한다")
    void getGroupDetail_activeMember_returnsDetail() {
        Group group = newGroup(creator);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);
        GroupMember membership = GroupMember.builder()
                .group(group).member(creator).role(GroupRole.OWNER).status(GroupMemberStatus.ACTIVE).build();
        when(groupMemberRepository.findAllByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership));

        GroupDetailResponseDto detail = groupService.getGroupDetail(GROUP_ID, MEMBER_ID);

        assertThat(detail.getMembers()).hasSize(1);
        assertThat(detail.getMembers().get(0).getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("leaveGroup — ACTIVE 멤버십을 LEFT로 전환한다")
    void leaveGroup_activeMembership_transitionsToLeft() {
        Group group = newGroup(creator);
        GroupMember membership = GroupMember.builder()
                .group(group).member(creator).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build();
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID)).thenReturn(Optional.of(membership));

        groupService.leaveGroup(GROUP_ID, MEMBER_ID);

        assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.LEFT);
    }

    @Test
    @DisplayName("leaveGroup — 가입 이력이 없으면 NOT_GROUP_MEMBER")
    void leaveGroup_noMembership_throws() {
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.leaveGroup(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("leaveGroup — 이미 LEFT 상태면 다시 탈퇴할 수 없다(NOT_GROUP_MEMBER)")
    void leaveGroup_alreadyLeft_throws() {
        Group group = newGroup(creator);
        GroupMember membership = GroupMember.builder()
                .group(group).member(creator).role(GroupRole.MEMBER).status(GroupMemberStatus.LEFT).build();
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID)).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> groupService.leaveGroup(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("assertActiveMember — ACTIVE 멤버면 예외 없이 통과한다")
    void assertActiveMember_activeMember_passes() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);

        assertThatCode(() -> groupService.assertActiveMember(GROUP_ID, MEMBER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertActiveMember — ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER")
    void assertActiveMember_notActiveMember_throws() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> groupService.assertActiveMember(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("createGroup — description 을 받아 저장하고 응답에 싣는다")
    void createGroup_carriesDescription() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(creator));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupResponseDto response = groupService.createGroup(MEMBER_ID,
                new CreateGroupRequestDto("그룹1", "우리 진짜 거북목 되지 말자"));

        assertThat(response.getDescription()).isEqualTo("우리 진짜 거북목 되지 말자");
    }

    @Test
    @DisplayName("createGroup — 뽑은 코드가 이미 쓰이면 다시 뽑는다 (존재 확인이 UNIQUE 앞의 첫 방어)")
    void createGroup_regeneratesWhenCodeAlreadyUsed() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(creator));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inviteCodeGenerator.generate()).thenReturn("TAKENCD1", "FRESHCD2");
        when(groupRepository.existsByInviteCode("TAKENCD1")).thenReturn(true);
        when(groupRepository.existsByInviteCode("FRESHCD2")).thenReturn(false);

        GroupResponseDto response = groupService.createGroup(MEMBER_ID, new CreateGroupRequestDto("그룹1"));

        assertThat(response.getInviteCode()).isEqualTo("FRESHCD2");
        verify(inviteCodeGenerator, times(2)).generate();
    }

    @Test
    @DisplayName("regenerateInviteCode — OWNER 면 새 코드로 바뀌고 이전 코드는 사라진다")
    void regenerateInviteCode_ownerGetsNewCode() {
        Group group = newGroup(creator);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(newMembership(group, creator, GroupRole.OWNER, GroupMemberStatus.ACTIVE)));
        when(inviteCodeGenerator.generate()).thenReturn("NEWCODE1");

        InviteCodeResponseDto response = groupService.regenerateInviteCode(GROUP_ID, MEMBER_ID);

        assertThat(response.getInviteCode()).isEqualTo("NEWCODE1");
        assertThat(group.getInviteCode()).isEqualTo("NEWCODE1").isNotEqualTo("TESTCD01");
    }

    @Test
    @DisplayName("regenerateInviteCode — ACTIVE 멤버지만 OWNER 가 아니면 NOT_GROUP_OWNER")
    void regenerateInviteCode_memberButNotOwner_throws() {
        Group group = newGroup(creator);
        Member plain = newMember(20L, "plain");
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, 20L))
                .thenReturn(Optional.of(newMembership(group, plain, GroupRole.MEMBER, GroupMemberStatus.ACTIVE)));

        assertThatThrownBy(() -> groupService.regenerateInviteCode(GROUP_ID, 20L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_OWNER);
        assertThat(group.getInviteCode()).isEqualTo("TESTCD01");
    }

    @Test
    @DisplayName("regenerateInviteCode — 멤버가 아니면(또는 LEFT) NOT_GROUP_MEMBER")
    void regenerateInviteCode_notMember_throws() {
        Group group = newGroup(creator);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, 20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.regenerateInviteCode(GROUP_ID, 20L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    // ---- admit / joinByInviteCode ----

    @Test
    @DisplayName("admit — 가입 이력이 없으면 MEMBER·ACTIVE 로 새 행을 만들고 MEMBER_JOINED 를 발신자 없이 발행한다")
    void admit_newMember_savesAndPublishes() {
        Group group = newGroup(creator);
        Member newcomer = newMember(20L, "newcomer");
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, 20L)).thenReturn(Optional.empty());
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupMember membership = groupService.admit(group, newcomer);

        assertThat(membership.getRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        // 시스템이 발행하는 이벤트라 senderId 는 null — GroupEventService.publish 계약.
        verify(groupEventService).publish(eq(GROUP_ID), isNull(), eq("MEMBER_JOINED"), contains("\"memberId\":20"));
    }

    @Test
    @DisplayName("admit — LEFT 였던 행은 새로 넣지 않고 되살린다 (UNIQUE(group_id, member_id) 재삽입 500 회귀 방지)")
    void admit_leftMember_reactivatesExistingRow() {
        Group group = newGroup(creator);
        Member returning = newMember(20L, "returning");
        GroupMember left = newMembership(group, returning, GroupRole.MEMBER, GroupMemberStatus.LEFT);
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, 20L)).thenReturn(Optional.of(left));

        groupService.admit(group, returning);

        assertThat(left.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        verify(groupMemberRepository, never()).save(any());
        verify(groupEventService).publish(eq(GROUP_ID), isNull(), eq("MEMBER_JOINED"), any());
    }

    @Test
    @DisplayName("admit — 이미 ACTIVE 면 ALREADY_GROUP_MEMBER, 이벤트도 안 나간다 (더블탭 두 번째 요청의 결말)")
    void admit_alreadyActive_throwsWithoutEvent() {
        Group group = newGroup(creator);
        Member member = newMember(20L, "member");
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, 20L))
                .thenReturn(Optional.of(newMembership(group, member, GroupRole.MEMBER, GroupMemberStatus.ACTIVE)));

        assertThatThrownBy(() -> groupService.admit(group, member))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_GROUP_MEMBER);
        verify(groupEventService, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("joinByInviteCode — 코드를 잠금 조회로 찾아 admit 하고 GroupResponseDto 를 돌려준다")
    void joinByInviteCode_success() {
        Group group = newGroup(creator);
        Member joiner = newMember(20L, "joiner");
        when(groupRepository.findByInviteCodeForUpdate("TESTCD01")).thenReturn(Optional.of(group));
        when(memberRepository.findById(20L)).thenReturn(Optional.of(joiner));
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, 20L)).thenReturn(Optional.empty());
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(groupInvitationRepository.findByGroupIdAndInviteeIdAndStatus(GROUP_ID, 20L, InvitationStatus.PENDING))
                .thenReturn(Optional.empty());

        GroupResponseDto response = groupService.joinByInviteCode(20L, new JoinGroupRequestDto("TESTCD01"));

        assertThat(response.getId()).isEqualTo(GROUP_ID);
        assertThat(response.getInviteCode()).isEqualTo("TESTCD01");
        verify(groupRepository).findByInviteCodeForUpdate("TESTCD01");
    }

    @Test
    @DisplayName("joinByInviteCode — 소문자·공백 입력은 정규화해서 찾는다")
    void joinByInviteCode_normalizesInput() {
        Group group = newGroup(creator);
        Member joiner = newMember(20L, "joiner");
        when(groupRepository.findByInviteCodeForUpdate("TESTCD01")).thenReturn(Optional.of(group));
        when(memberRepository.findById(20L)).thenReturn(Optional.of(joiner));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        groupService.joinByInviteCode(20L, new JoinGroupRequestDto("  testcd01 "));

        verify(groupRepository).findByInviteCodeForUpdate("TESTCD01");
    }

    @Test
    @DisplayName("joinByInviteCode — 없는 코드면 INVALID_INVITE_CODE")
    void joinByInviteCode_unknownCode_throws() {
        when(groupRepository.findByInviteCodeForUpdate("NOPE1234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.joinByInviteCode(20L, new JoinGroupRequestDto("NOPE1234")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    @DisplayName("joinByInviteCode — 같은 (그룹, 회원)의 PENDING 초대가 있으면 ACCEPTED 로 닫는다")
    void joinByInviteCode_closesPendingInvitation() {
        Group group = newGroup(creator);
        Member joiner = newMember(20L, "joiner");
        GroupInvitation pending = GroupInvitation.builder().group(group).inviter(creator).invitee(joiner).build();
        when(groupRepository.findByInviteCodeForUpdate("TESTCD01")).thenReturn(Optional.of(group));
        when(memberRepository.findById(20L)).thenReturn(Optional.of(joiner));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(groupInvitationRepository.findByGroupIdAndInviteeIdAndStatus(GROUP_ID, 20L, InvitationStatus.PENDING))
                .thenReturn(Optional.of(pending));

        groupService.joinByInviteCode(20L, new JoinGroupRequestDto("TESTCD01"));

        assertThat(pending.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    private GroupMember newMembership(Group group, Member member, GroupRole role, GroupMemberStatus status) {
        return GroupMember.builder().group(group).member(member).role(role).status(status).build();
    }

    private Group newGroup(Member creator) {
        return Group.builder().id(GROUP_ID).name("그룹").inviteCode("TESTCD01").createdBy(creator).build();
    }

    private Member newMember(Long id, String username) {
        return Member.builder().id(id).email(username + "@test.com").username(username)
                .password("encoded-password").role(UserRole.USER).build();
    }
}
