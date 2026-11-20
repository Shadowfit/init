package com.shadowfit.integration;

import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.group.GroupEventService;
import com.shadowfit.service.group.GroupService;
import com.shadowfit.service.group.GroupWithdrawalService;
import com.shadowfit.service.member.MemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원 탈퇴 때 그룹 쪽 정리 — {@code GroupWithdrawalService} (점검 2026-11-18).
 *
 * <p>핵심은 «FK CASCADE 에 맡기면 안 되는 세 가지» 를 각각 고정하는 것이다: 생성자 탈퇴로 모임이 통째로 지워지는 것,
 * 그룹장이 멤버를 두고 나가는 것, 피드 사진 파일이 남는 것.
 */
@SpringBootTest
@Transactional
@DisplayName("회원 탈퇴 — 그룹 정리")
class GroupWithdrawalIntegrationTest {

    @Autowired private MemberService memberService;
    @Autowired private GroupWithdrawalService groupWithdrawalService;
    @Autowired private GroupService groupService;
    @Autowired private GroupEventService groupEventService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private GroupEventRepository groupEventRepository;
    @Autowired private EntityManager em;

    private Member alice;
    private Member bob;

    @BeforeEach
    void setUp() {
        alice = member("alice-wd@test.com", "alice");
        bob = member("bob-wd@test.com", "bob");
    }

    private Member member(String email, String username) {
        return memberRepository.saveAndFlush(Member.builder()
                .email(email).username(username).password("pw").role(UserRole.USER).build());
    }

    private Group groupOf(Member creator) {
        Long id = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("모임")).getId();
        return groupRepository.findById(id).orElseThrow();
    }

    private void join(Group group, Member who) {
        groupService.admit(group, who);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("생성자가 그룹장을 넘기고 탈퇴해도 모임과 다른 멤버는 남는다 (created_by CASCADE 차단)")
    void creatorWhoTransferredOwnership_withdraws_groupSurvives() {
        Group group = groupOf(alice);
        join(group, bob);
        groupService.transferOwnership(group.getId(), alice.getId(), bob.getId());
        // alice 는 이제 MEMBER 지만 created_by 는 여전히 alice 다 — 예전에는 여기서 탈퇴하면 모임이 통째로 사라졌다.
        groupEventService.publish(group.getId(), bob.getId(), GroupEventTypes.SESSION_SHARED, "{\"memo\":\"bob 글\"}");
        flushAndClear();

        memberService.deleteAccount(alice.getEmail());
        flushAndClear();

        Group after = groupRepository.findById(group.getId()).orElseThrow();
        assertThat(after.getCreatedBy().getId())
                .as("생성자 표시가 현재 그룹장(bob)으로 옮겨졌다")
                .isEqualTo(bob.getId());
        GroupMember bobMembership = groupMemberRepository.findByGroupIdAndMemberId(group.getId(), bob.getId()).orElseThrow();
        assertThat(bobMembership.getRole()).isEqualTo(GroupRole.OWNER);
        assertThat(bobMembership.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(groupEventRepository.findPayloadsByGroupId(group.getId()))
                .as("다른 멤버의 글이 같이 사라지지 않는다")
                .anyMatch(p -> p.contains("bob 글"));
        assertThat(memberRepository.findByEmail(alice.getEmail())).isEmpty();
    }

    @Test
    @DisplayName("다른 ACTIVE 멤버가 있는 모임의 그룹장은 탈퇴할 수 없다 — 양도 먼저(409), 아무것도 지워지지 않는다")
    void ownerWithOtherMembers_cannotWithdraw() {
        Group group = groupOf(alice);
        join(group, bob);
        flushAndClear();

        assertThatThrownBy(() -> memberService.deleteAccount(alice.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OWNER_MUST_TRANSFER_FIRST);

        assertThat(memberRepository.findByEmail(alice.getEmail())).isPresent();
        assertThat(groupRepository.findById(group.getId())).isPresent();
    }

    @Test
    @DisplayName("혼자 남은 그룹장이 탈퇴하면 모임도 지워진다 (leaveGroup 과 같은 정책)")
    void soleOwner_withdraws_groupDeleted() {
        Group group = groupOf(alice);
        flushAndClear();

        memberService.deleteAccount(alice.getEmail());
        flushAndClear();

        assertThat(groupRepository.findById(group.getId())).isEmpty();
    }

    @Test
    @DisplayName("탈퇴자가 쓴 글은 지워지고(본문이 익명으로 남지 않는다), 사진 파일명이 돌아온다")
    void authoredPosts_deleted_andPhotoNamesReturned() {
        Group group = groupOf(bob);
        join(group, alice);
        String photo = "11111111-2222-3333-4444-555555555555.jpg";
        groupEventService.publish(group.getId(), alice.getId(), GroupEventTypes.SESSION_SHARED,
                "{\"caption\":\"alice 글\",\"photoUrl\":\"/feed-photos/" + photo + "\"}");
        groupEventService.publish(group.getId(), bob.getId(), GroupEventTypes.SESSION_SHARED,
                "{\"caption\":\"bob 글\",\"photoUrl\":\"/feed-photos/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png\"}");
        flushAndClear();

        List<String> photos = groupWithdrawalService.detachMember(alice.getId());
        flushAndClear();

        assertThat(photos).containsExactly(photo);
        assertThat(groupEventRepository.findPayloadsByGroupId(group.getId()))
                .noneMatch(p -> p.contains("alice 글"))
                .anyMatch(p -> p.contains("bob 글"));
    }

    @Test
    @DisplayName("깨졌거나 사진 없는 payload 는 무시한다")
    void photoNameOf_ignoresMalformedAndMissing() {
        assertThat(groupWithdrawalService.photoNameOf(null)).isNull();
        assertThat(groupWithdrawalService.photoNameOf("")).isNull();
        assertThat(groupWithdrawalService.photoNameOf("not json")).isNull();
        assertThat(groupWithdrawalService.photoNameOf("{\"caption\":\"x\"}")).isNull();
        assertThat(groupWithdrawalService.photoNameOf("{\"photoUrl\":null}")).isNull();
        assertThat(groupWithdrawalService.photoNameOf("{\"photoUrl\":\"/feed-photos/x.jpg\"}")).isEqualTo("x.jpg");
    }
}
