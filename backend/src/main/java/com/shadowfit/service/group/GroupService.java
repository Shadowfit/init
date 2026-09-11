package com.shadowfit.service.group;

import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.GroupDetailResponseDto;
import com.shadowfit.dto.group.InviteCodeResponseDto;
import com.shadowfit.dto.group.JoinGroupRequestDto;
import com.shadowfit.dto.group.GroupResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MemberRepository memberRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final GroupInvitationRepository groupInvitationRepository;
    private final GroupEventService groupEventService;
    private final ObjectMapper objectMapper;

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
     * 코드로 모임에 참여한다 (social-cheer-and-group-feed.md §3-F — 승인 없이 바로 ACTIVE).
     *
     * <p>입력은 {@code trim()} + 대문자로 정규화한다 — 코드 알파벳({@link InviteCodeGenerator#ALPHABET})에
     * 소문자가 없어 의미 충돌이 없고, 카톡으로 받은 코드를 손으로 치는 사용자가 소문자·공백 때문에
     * 404 를 받지 않게 한다.
     *
     * <p>그룹 행을 {@code FOR UPDATE} 로 잡고 시작한다 — 같은 그룹에 대한 가입을 직렬화해 더블탭
     * 두 번째가 «이미 ACTIVE» 를 보고 409 로 끝나게 하려는 것({@link #admit} 참고). 이 잠금은
     * 어차피 끝에 MEMBER_JOINED 를 발행하며 잡아야 하는 그 행이라, 앞으로 당길 뿐 새로 생기는 게
     * 아니다.
     */
    public GroupResponseDto joinByInviteCode(Long memberId, JoinGroupRequestDto request) {
        String code = request.getInviteCode().trim().toUpperCase();
        Group group = groupRepository.findByInviteCodeForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITE_CODE));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        admit(group, member);

        // 코드로 들어온 것도 초대에 응한 것이다 — 그 (그룹, 회원)의 PENDING 초대를 닫아 두지 않으면
        // 나중에 수락했을 때 admit() 이 ALREADY_GROUP_MEMBER 를 던지거나(지금), 이전 구조에선
        // MEMBER_JOINED 가 한 번 더 발행됐다.
        groupInvitationRepository.findByGroupIdAndInviteeIdAndStatus(group.getId(), memberId, InvitationStatus.PENDING)
                .ifPresent(invitation -> invitation.accept());

        return GroupResponseDto.from(group);
    }

    /**
     * 회원을 그룹의 ACTIVE 멤버로 들인다 — 초대 수락({@code GroupInvitationService.accept})과 코드 참여
     * ({@link #joinByInviteCode})가 함께 쓰는 유일한 가입 경로.
     *
     * <p><b>호출자는 {@code group} 을 {@code FOR UPDATE} 로 잡은 트랜잭션 안에서 불러야 한다.</b>
     * 아래 «있으면 되살리고 없으면 만든다» 는 check-then-act 라, 잠금 없이는 같은 회원의 두 요청이
     * 둘 다 «없음» 을 보고 INSERT 해 UNIQUE(group_id, member_id) 위반으로 500 이 난다(#195 계열).
     * 그 위반을 catch 해 재시도하는 방식은 flush 실패로 Hibernate 세션이 손상돼 쓸 수 없다
     * ({@code DailyLogRepository} 실측) — 그래서 뒤에서 잡는 대신 앞에서 줄을 세운다.
     *
     * <p>{@code leaveGroup()} 은 행을 LEFT 로만 남기므로 재가입 시 새 행을 넣으면 같은 UNIQUE 에
     * 걸린다 — 기존 행이 있으면 {@code rejoin()} 으로 되살린다. rejoin 이 role 을 MEMBER 로 덮는
     * 문제(OWNER 가 나갔다 오면 그룹장이 사라짐)는 #721 로 분리.
     */
    public GroupMember admit(Group group, Member member) {
        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(group.getId(), member.getId())
                .map(existing -> {
                    if (existing.getStatus() == GroupMemberStatus.ACTIVE) {
                        throw new BusinessException(ErrorCode.ALREADY_GROUP_MEMBER);
                    }
                    existing.rejoin();
                    return existing;
                })
                .orElseGet(() -> groupMemberRepository.save(GroupMember.builder()
                        .group(group)
                        .member(member)
                        .role(GroupRole.MEMBER)
                        .status(GroupMemberStatus.ACTIVE)
                        .build()));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("memberId", member.getId());
        payload.put("username", member.getUsername());
        groupEventService.publish(group.getId(), null, "MEMBER_JOINED", payload.toString());
        return membership;
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