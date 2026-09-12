package com.shadowfit.repository.group;

import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code existsByGroupIdAndMemberIdAndStatus}가 WebSocket 핸드셰이크 인가와 REST 인가
 * 양쪽의 근거이므로({@code JwtHandshakeInterceptor}, {@code GroupService.assertActiveMember}),
 * ACTIVE/LEFT 경계를 정확히 가르는지가 이 레포지토리 테스트의 핵심이다.
 */
@SpringBootTest
@Transactional
@DisplayName("GroupMemberRepository 테스트")
class GroupMemberRepositoryTest {

    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private MemberRepository memberRepository;

    private Group group;
    private Member activeMember;
    private Member leftMember;
    private Member stranger;

    @BeforeEach
    void setUp() {
        Member creator = memberRepository.saveAndFlush(newMember("creator@test.com", "creator"));
        activeMember = memberRepository.saveAndFlush(newMember("active@test.com", "active"));
        leftMember = memberRepository.saveAndFlush(newMember("left@test.com", "left"));
        stranger = memberRepository.saveAndFlush(newMember("stranger@test.com", "stranger"));

        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("TESTCD01").createdBy(creator).build());

        groupMemberRepository.saveAndFlush(newGroupMember(activeMember, GroupMemberStatus.ACTIVE));
        groupMemberRepository.saveAndFlush(newGroupMember(leftMember, GroupMemberStatus.LEFT));
    }

    @Test
    @DisplayName("ACTIVE 멤버는 existsByGroupIdAndMemberIdAndStatus(ACTIVE) 가 true")
    void existsActive_forActiveMember_isTrue() {
        assertThat(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(
                group.getId(), activeMember.getId(), GroupMemberStatus.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("탈퇴(LEFT)한 멤버는 existsByGroupIdAndMemberIdAndStatus(ACTIVE) 가 false")
    void existsActive_forLeftMember_isFalse() {
        assertThat(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(
                group.getId(), leftMember.getId(), GroupMemberStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("가입한 적 없는 사용자는 existsByGroupIdAndMemberIdAndStatus(ACTIVE) 가 false")
    void existsActive_forStranger_isFalse() {
        assertThat(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(
                group.getId(), stranger.getId(), GroupMemberStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("findAllByGroupIdAndStatus(ACTIVE) — LEFT 멤버는 제외하고 반환한다")
    void findAllByGroupIdAndStatus_excludesLeftMembers() {
        assertThat(groupMemberRepository.findAllByGroupIdAndStatus(group.getId(), GroupMemberStatus.ACTIVE))
                .extracting(gm -> gm.getMember().getId())
                .containsExactly(activeMember.getId());
    }

    @Test
    @DisplayName("findAllByMemberIdAndStatus(ACTIVE) — 탈퇴한 그룹은 내 목록에서 빠진다")
    void findAllByMemberIdAndStatus_excludesLeftGroups() {
        assertThat(groupMemberRepository.findAllByMemberIdAndStatus(leftMember.getId(), GroupMemberStatus.ACTIVE))
                .isEmpty();
        assertThat(groupMemberRepository.findAllByMemberIdAndStatus(activeMember.getId(), GroupMemberStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    @DisplayName("findByGroupIdAndMemberId — 상태와 무관하게 가입 이력 자체를 찾는다")
    void findByGroupIdAndMemberId_findsRegardlessOfStatus() {
        assertThat(groupMemberRepository.findByGroupIdAndMemberId(group.getId(), leftMember.getId()))
                .isPresent()
                .get()
                .extracting(GroupMember::getStatus)
                .isEqualTo(GroupMemberStatus.LEFT);
    }

    // --- shareGroupWithStatus — 재촉 권한(§3-G)의 유일한 근거. ACTIVE 둘 다여야 참이고, 자기 자신은 참이다.

    @Test
    @DisplayName("shareGroupWithStatus — 같은 그룹 ACTIVE 둘이면 true, 순서 무관")
    void shareGroup_bothActive_isTrue() {
        Member second = memberRepository.saveAndFlush(newMember("second@test.com", "second"));
        groupMemberRepository.saveAndFlush(newGroupMember(second, GroupMemberStatus.ACTIVE));

        assertThat(groupMemberRepository.shareGroupWithStatus(activeMember.getId(), second.getId(), GroupMemberStatus.ACTIVE)).isTrue();
        assertThat(groupMemberRepository.shareGroupWithStatus(second.getId(), activeMember.getId(), GroupMemberStatus.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("shareGroupWithStatus — 한쪽이 LEFT 거나 가입한 적 없으면 false")
    void shareGroup_leftOrStranger_isFalse() {
        assertThat(groupMemberRepository.shareGroupWithStatus(activeMember.getId(), leftMember.getId(), GroupMemberStatus.ACTIVE)).isFalse();
        assertThat(groupMemberRepository.shareGroupWithStatus(activeMember.getId(), stranger.getId(), GroupMemberStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("shareGroupWithStatus — 서로 다른 그룹에만 있으면 false")
    void shareGroup_differentGroups_isFalse() {
        Member other = memberRepository.saveAndFlush(newMember("other@test.com", "other"));
        Group otherGroup = groupRepository.saveAndFlush(Group.builder().name("다른 그룹").inviteCode("TESTCD02").createdBy(other).build());
        groupMemberRepository.saveAndFlush(GroupMember.builder().group(otherGroup).member(other)
                .role(GroupRole.OWNER).status(GroupMemberStatus.ACTIVE).build());

        assertThat(groupMemberRepository.shareGroupWithStatus(activeMember.getId(), other.getId(), GroupMemberStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("shareGroupWithStatus — 자기 자신은 true (호출자가 먼저 걸러야 하는 이유)")
    void shareGroup_self_isTrue() {
        assertThat(groupMemberRepository.shareGroupWithStatus(activeMember.getId(), activeMember.getId(), GroupMemberStatus.ACTIVE)).isTrue();
    }

    private GroupMember newGroupMember(Member member, GroupMemberStatus status) {
        return GroupMember.builder().group(group).member(member).role(GroupRole.MEMBER).status(status).build();
    }

    private Member newMember(String email, String username) {
        return Member.builder().email(email).username(username)
                .password("encoded-password").role(UserRole.USER).build();
    }
}
