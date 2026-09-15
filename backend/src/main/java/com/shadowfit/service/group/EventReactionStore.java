package com.shadowfit.service.group;

import com.shadowfit.model.group.EventReaction;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.ReactionKind;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.EventReactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리액션 쓰기 두 걸음을 <b>각각 자기 트랜잭션</b>으로 — {@code PushTokenStore} 와 같은 이유. INSERT 가
 * UNIQUE(event_id, member_id, kind) 에 걸렸을 때(더블탭) 같은 트랜잭션 안에서 이어갈 수 없으므로, 시도 하나 =
 * 트랜잭션 하나로 끊고 호출자({@link GroupFeedService})가 예외를 «이미 있다» 로 해석한다.
 */
@Component
@RequiredArgsConstructor
public class EventReactionStore {

    private final EventReactionRepository eventReactionRepository;

    /** 새 행 INSERT. UNIQUE 위반은 그대로 던진다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(GroupEvent event, Member member, ReactionKind kind) {
        eventReactionRepository.saveAndFlush(EventReaction.builder().event(event).member(member).kind(kind).build());
    }

    /** 있으면 지우고 1, 없으면 0 — 둘 다 성공(멱등). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int delete(Long eventId, Long memberId, ReactionKind kind) {
        return eventReactionRepository.deleteByEventIdAndMemberIdAndKind(eventId, memberId, kind);
    }
}
