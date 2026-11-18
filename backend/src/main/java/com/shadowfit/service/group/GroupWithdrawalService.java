package com.shadowfit.service.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 회원 탈퇴 때 그룹 쪽에서 해야 할 일 — {@code MemberService.deleteAccount} 의 트랜잭션 <b>안에서</b> 부른다.
 *
 * <p>FK CASCADE 에 맡기면 안 되는 이유 세 가지(점검 2026-11-18):
 * <ol>
 *   <li><b>생성자가 탈퇴하면 그룹이 통째로 지워진다.</b> {@code workout_groups.created_by} 가
 *       {@code NOT NULL ... ON DELETE CASCADE} 라서, 그룹장 자리를 다른 사람에게 넘겨 두었어도 <i>생성자</i>
 *       행이 지워지는 순간 다른 멤버의 피드·멤버십까지 사라진다.</li>
 *   <li><b>그룹장이 다른 멤버를 두고 탈퇴할 수 있었다.</b> {@code leaveGroup} 에는 «양도 먼저» 가드가 있는데
 *       탈퇴 경로에는 없었다.</li>
 *   <li><b>피드 사진 파일이 안 지워졌다.</b> 파일은 디스크에 있어 DB CASCADE 의 대상이 아니다.</li>
 * </ol>
 *
 * <p>정책(2026-11-18 확정): 그룹장은 <b>양도 후 탈퇴</b>(다른 ACTIVE 멤버가 있으면 409), 혼자 남은 그룹장은
 * 모임을 지운다({@code leaveGroup} 과 같다), 탈퇴자가 쓴 글 본문은 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupWithdrawalService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupEventRepository groupEventRepository;
    private final FeedPhotoStorage feedPhotoStorage;
    private final ObjectMapper objectMapper;

    /**
     * 탈퇴할 회원의 그룹 관계를 정리하고, 커밋 뒤에 지워야 할 피드 사진 파일명을 돌려준다.
     *
     * @throws BusinessException {@code OWNER_MUST_TRANSFER_FIRST} — 다른 ACTIVE 멤버가 있는 모임의 그룹장일 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> detachMember(Long memberId) {
        // 락 순서를 고정한다(id 오름차순) — 다른 트랜잭션(양도·참여)이 같은 행을 반대 순서로 잡아 교착하는 것을 막는다.
        Set<Long> groupIds = new TreeSet<>();
        groupIds.addAll(groupMemberRepository.findGroupIdsByMemberIdAndRoleAndStatus(
                memberId, GroupRole.OWNER, GroupMemberStatus.ACTIVE));
        groupIds.addAll(groupRepository.findIdsByCreatedById(memberId));

        Set<String> photos = new LinkedHashSet<>();

        for (Long groupId : groupIds) {
            Group group = groupRepository.findByIdForUpdate(groupId).orElse(null);
            if (group == null) {
                continue; // 앞 단계에서 이미 지워짐
            }
            GroupMember mine = groupMemberRepository.findByGroupIdAndMemberId(groupId, memberId).orElse(null);
            boolean activeOwner = mine != null
                    && mine.getRole() == GroupRole.OWNER && mine.getStatus() == GroupMemberStatus.ACTIVE;

            if (activeOwner) {
                if (groupMemberRepository.existsByGroupIdAndStatusAndMemberIdNot(
                        groupId, GroupMemberStatus.ACTIVE, memberId)) {
                    throw new BusinessException(ErrorCode.OWNER_MUST_TRANSFER_FIRST);
                }
                // 혼자 남은 그룹장 — 모임을 지운다. 모임에 딸린 사진도 같이 지운다(AdminInsightService 와 같은 방침).
                groupEventRepository.findPayloadsByGroupId(groupId)
                        .forEach(p -> addPhotoName(photos, p));
                groupRepository.delete(group);
                continue;
            }

            if (group.getCreatedBy() != null && memberId.equals(group.getCreatedBy().getId())) {
                // 생성자지만 그룹장은 아니다 — 생성자 표시를 현재 그룹장으로 옮겨 CASCADE 에서 빼낸다.
                GroupMember owner = groupMemberRepository
                        .findFirstByGroupIdAndRoleAndStatus(groupId, GroupRole.OWNER, GroupMemberStatus.ACTIVE)
                        .orElse(null);
                if (owner == null) {
                    // 정상 상태에서는 없다(그룹장은 양도 후에만 나간다). 옮길 곳이 없으면 CASCADE 를 막을 수 없으니
                    // 조용히 지우지 말고 실패시켜 사람이 보게 한다.
                    log.error("그룹장이 없는 모임의 생성자가 탈퇴를 시도 — groupId={}, memberId={}", groupId, memberId);
                    throw new IllegalStateException("그룹장이 없는 모임: " + groupId);
                }
                group.reassignCreator(owner.getMember());
            }
        }

        // 남은 모임에서 내가 쓴 글 — 사진 이름을 먼저 읽고 본문을 지운다.
        groupEventRepository.findPayloadsBySenderId(memberId).forEach(p -> addPhotoName(photos, p));
        groupEventRepository.deleteBySenderId(memberId);

        return new ArrayList<>(photos);
    }

    /** 커밋이 확정된 뒤에 사진 파일을 지운다 — 롤백된 탈퇴가 파일만 지워 버리는 일을 막는다. */
    public void deleteFilesAfterCommit(List<String> photoNames) {
        if (photoNames == null || photoNames.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                photoNames.forEach(feedPhotoStorage::deleteQuietly);
            }
        });
    }

    private void addPhotoName(Set<String> sink, String payload) {
        String name = photoNameOf(payload);
        if (name != null) {
            sink.add(name);
        }
    }

    /** payload JSON 의 {@code photoUrl}("/feed-photos/&lt;이름&gt;")에서 파일 이름. 없거나 깨졌으면 null. */
    public String photoNameOf(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode url = objectMapper.readTree(payload).get("photoUrl");
            if (url == null || url.isNull()) {
                return null;
            }
            String s = url.asText();
            return s.substring(s.lastIndexOf('/') + 1);
        } catch (Exception e) {
            return null;
        }
    }
}
