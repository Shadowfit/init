package com.shadowfit.service.group;

import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.GroupDetailResponseDto;
import com.shadowfit.dto.group.InviteCodeResponseDto;
import com.shadowfit.dto.group.GroupResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MemberRepository memberRepository;
    private final InviteCodeGenerator inviteCodeGenerator;

    public GroupResponseDto createGroup(Long creatorId, CreateGroupRequestDto request) {
        Member creator = memberRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Group group = groupRepository.save(Group.builder()
                .name(request.getName())
                .description(request.getDescription())
                .inviteCode(freshInviteCode())
                .createdBy(creator)
                .build());

        groupMemberRepository.save(GroupMember.builder()
                .group(group)
                .member(creator)
                .role(GroupRole.OWNER)
                .status(GroupMemberStatus.ACTIVE)
                .build());

        return GroupResponseDto.from(group);
    }

    @Transactional(readOnly = true)
    public List<GroupResponseDto> listMyGroups(Long memberId) {
        return groupMemberRepository.findAllByMemberIdAndStatus(memberId, GroupMemberStatus.ACTIVE).stream()
                .map(GroupMember::getGroup)
                .map(GroupResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupDetailResponseDto getGroupDetail(Long groupId, Long requesterId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        assertActiveMember(groupId, requesterId);

        List<GroupMember> members = groupMemberRepository.findAllByGroupIdAndStatus(groupId, GroupMemberStatus.ACTIVE);
        return GroupDetailResponseDto.from(group, members);
    }

    /**
     * 초대 코드를 새 값으로 갈아끼운다 — 코드가 유출됐을 때 그룹장이 쓰는 경로. 코드는 그룹당
     * 1개 고정이라(3-F 결정) 재발급이 곧 이전 코드의 폐기다. OWNER 만 할 수 있다 — 코드를 아는
     * 사람은 승인 없이 들어오므로, 그걸 무효화할 권한도 만든 사람에게만 둔다.
     */
    public InviteCodeResponseDto regenerateInviteCode(Long groupId, Long requesterId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(groupId, requesterId)
                .filter(gm -> gm.getStatus() == GroupMemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_GROUP_MEMBER));
        if (membership.getRole() != GroupRole.OWNER) {
            throw new BusinessException(ErrorCode.NOT_GROUP_OWNER);
        }

        group.regenerateInviteCode(freshInviteCode());
        return new InviteCodeResponseDto(group.getInviteCode());
    }

    /**
     * 이미 쓰이는 코드가 아닐 때까지 뽑는다 — 32⁸ 공간이라 사실상 첫 번에 통과한다.
     *
     * <p>존재 확인 → 저장은 check-then-act 라 그 틈의 레이스는 {@code uk_workout_groups_invite_code}
     * 가 막는다(제약이 최종 방어선 — {@code goals} 와 같은 결). 그 위반을 catch 해 같은
     * 트랜잭션에서 다시 저장하는 방식은 <b>쓰지 않는다</b>: flush 실패 뒤 Hibernate 세션이
     * 손상돼 후속 쿼리가 깨진다({@code DailyLogRepository} 주석, 실측). 두 생성이 같은 순간
     * 같은 8자리를 뽑을 확률은 1.1×10¹² 분의 1 이라 그 경우는 예외로 둔다.
     */
    private String freshInviteCode() {
        String code;
        do {
            code = inviteCodeGenerator.generate();
        } while (groupRepository.existsByInviteCode(code));
        return code;
    }

    public void leaveGroup(Long groupId, Long memberId) {
        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(groupId, memberId)
                .filter(gm -> gm.getStatus() == GroupMemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_GROUP_MEMBER));

        membership.leave();
    }

    // 백필 등 다른 컨트롤러 엔드포인트에서도 "그룹 멤버만 접근 가능"을 재사용한다.
    @Transactional(readOnly = true)
    public void assertActiveMember(Long groupId, Long memberId) {
        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, memberId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }
    }
}