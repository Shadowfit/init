package com.shadowfit.service.group;

import com.shadowfit.dto.group.GroupFeedResponseDto;
import com.shadowfit.dto.group.ReactionSummaryDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.ReactionKind;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.EventReactionRepository;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.member.AdminMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 모임 피드 읽기 + 리액션 — social-cheer-and-group-feed.md §4-5.
 *
 * <p>피드는 {@code group_events} 를 최신순 keyset 으로 읽고 리액션 카운트·내 리액션을 쿼리 둘로 붙인다
 * (한 페이지에 N+1 없음). 리액션 쓰기는 멱등 — PUT 은 «있으면 그대로», DELETE 는 «없으면 그대로», 둘 다 갱신된
 * 요약을 돌려준다. 알림·WS 발행은 없다(§3-D 결정).
 *
 * <p>권한은 «같은 그룹 ACTIVE» 하나 — 피드 읽기·리액션 모두 {@link GroupService#assertActiveMember}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupFeedService {

    private final GroupService groupService;
    private final GroupEventRepository groupEventRepository;
    private final EventReactionRepository eventReactionRepository;
    private final EventReactionStore eventReactionStore;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public GroupFeedResponseDto feed(Long groupId, Long memberId, Long beforeSeq, int size) {
        groupService.assertActiveMember(groupId, memberId);
        // 기본 20·상한 100 — 알림 목록과 같은 값(관례 통일, §4-5 ②).
        int safeSize = size <= 0 ? AdminMemberService.DEFAULT_PAGE_SIZE : Math.min(size, AdminMemberService.MAX_PAGE_SIZE);
        long cursor = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;

        List<GroupEvent> events = groupEventRepository
                .findAllByGroupIdAndSeqLessThanOrderBySeqDesc(groupId, cursor, PageRequest.of(0, safeSize));
        Map<Long, ReactionSummaryDto> summaries = summarize(events, memberId);

        List<GroupFeedResponseDto.Item> items = events.stream()
                .map(e -> GroupFeedResponseDto.Item.of(e, summaries.get(e.getId())))
                .toList();
        Long next = items.size() == safeSize ? items.get(items.size() - 1).getSeq() : null;
        return GroupFeedResponseDto.builder().items(items).nextBeforeSeq(next).build();
    }

    /** 있으면 그대로, 없으면 만든다 — 어느 쪽이든 갱신된 요약(§4-5 ④). */
    public ReactionSummaryDto react(Long groupId, Long seq, Long memberId, ReactionKind kind) {
        GroupEvent event = target(groupId, seq, memberId);
        if (!eventReactionRepository.existsByEventIdAndMemberIdAndKind(event.getId(), memberId, kind)) {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            try {
                eventReactionStore.insert(event, member, kind);
            } catch (DataIntegrityViolationException e) {
                // 무결성 위반은 둘 중 하나다 — 더블탭(UNIQUE: 위 exists 와 INSERT 사이에 같은 요청이 먼저 넣었다)이면
                // 원하는 상태(«하나 있음»)가 이미 됐으므로 성공이고, FK(그새 그룹 삭제·회원 탈퇴)면 저장이 안 된 것이라
                // 200 을 주면 거짓이다. 행이 있는지로 가른다.
                if (!eventReactionRepository.existsByEventIdAndMemberIdAndKind(event.getId(), memberId, kind)) {
                    throw e;
                }
                log.debug("리액션 중복 INSERT — 이미 있음 (eventId={}, memberId={}, kind={})", event.getId(), memberId, kind);
            }
        }
        return summaryOf(event, memberId);
    }

    /** 있으면 지우고, 없으면 그대로 — 어느 쪽이든 갱신된 요약. */
    public ReactionSummaryDto unreact(Long groupId, Long seq, Long memberId, ReactionKind kind) {
        GroupEvent event = target(groupId, seq, memberId);
        eventReactionStore.delete(event.getId(), memberId, kind);
        return summaryOf(event, memberId);
    }

    private GroupEvent target(Long groupId, Long seq, Long memberId) {
        groupService.assertActiveMember(groupId, memberId);
        return groupEventRepository.findByGroupIdAndSeq(groupId, seq)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_EVENT_NOT_FOUND));
    }

    private ReactionSummaryDto summaryOf(GroupEvent event, Long memberId) {
        return summarize(List.of(event), memberId).get(event.getId());
    }

    /** 이벤트 목록의 요약을 쿼리 둘로 — 카운트(GROUP BY)와 내 것(IN + member). 없는 종류는 0 으로 채운다(⑨). */
    private Map<Long, ReactionSummaryDto> summarize(List<GroupEvent> events, Long memberId) {
        Map<Long, Map<ReactionKind, Long>> counts = new HashMap<>();
        Map<Long, List<ReactionKind>> mine = new HashMap<>();
        for (GroupEvent e : events) {
            Map<ReactionKind, Long> zero = new EnumMap<>(ReactionKind.class);
            for (ReactionKind k : ReactionKind.values()) {
                zero.put(k, 0L);
            }
            counts.put(e.getId(), zero);
            mine.put(e.getId(), new ArrayList<>());
        }
        if (!events.isEmpty()) {
            List<Long> ids = events.stream().map(GroupEvent::getId).toList();
            for (EventReactionRepository.KindCount c : eventReactionRepository.countByEventIds(ids)) {
                counts.get(c.eventId()).put(c.kind(), c.count());
            }
            for (EventReactionRepository.MemberKind m : eventReactionRepository.findKindsByEventIdsAndMemberId(ids, memberId)) {
                mine.get(m.eventId()).add(m.kind());
            }
        }
        return events.stream().collect(Collectors.toMap(GroupEvent::getId, e -> ReactionSummaryDto.builder()
                .reactions(counts.get(e.getId()))
                .myReactions(mine.get(e.getId()))
                .build()));
    }
}
