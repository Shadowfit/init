package com.shadowfit.service.group;

import com.shadowfit.dto.group.CreateInvitationRequestDto;
import com.shadowfit.dto.group.InvitationResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.GroupInvitationRepository;
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
public class GroupInvitationService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupInvitationRepository groupInvitationRepository;
    private final MemberRepository memberRepository;
    private final GroupService groupService;

    public InvitationResponseDto invite(Long groupId, Long inviterId, CreateInvitationRequestDto request) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, inviterId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }

        Member inviter = memberRepository.findById(inviterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Member invitee = memberRepository.findById(request.getInviteeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, invitee.getId(), GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.ALREADY_GROUP_MEMBER);
        }
        if (groupInvitationRepository.existsByGroupIdAndInviteeIdAndStatus(groupId, invitee.getId(), InvitationStatus.PENDING)) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_PENDING);
        }

        GroupInvitation invitation = groupInvitationRepository.save(GroupInvitation.builder()
                .group(group)
                .inviter(inviter)
                .invitee(invitee)
                .build());

        return InvitationResponseDto.from(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponseDto> listMyInvitations(Long memberId) {
        return groupInvitationRepository.findAllByInviteeIdAndStatus(memberId, InvitationStatus.PENDING).stream()
                .map(InvitationResponseDto::from)
                .toList();
    }

    public void accept(Long invitationId, Long inviteeId) {
        GroupInvitation invitation = getRespondableInvitation(invitationId, inviteeId);

        invitation.accept();

        // 가입(LEFT 되살리기 포함)과 MEMBER_JOINED 발행은 코드 참여와 공유한다 — GroupService.admit.
        // admit 은 그룹 행이 FOR UPDATE 로 잠긴 상태를 전제하므로 여기서 잠그고 넘긴다(더블탭 500 → 409).
        Group group = groupRepository.findByIdForUpdate(invitation.getGroup().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
        groupService.admit(group, invitation.getInvitee());
    }

    public void decline(Long invitationId, Long inviteeId) {
        GroupInvitation invitation = getRespondableInvitation(invitationId, inviteeId);
        invitation.decline();
    }

    private GroupInvitation getRespondableInvitation(Long invitationId, Long inviteeId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));

        if (!invitation.getInvitee().getId().equals(inviteeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_RESPONDED);
        }
        return invitation;
    }
}