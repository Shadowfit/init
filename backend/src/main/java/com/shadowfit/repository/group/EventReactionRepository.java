package com.shadowfit.repository.group;

import com.shadowfit.model.group.EventReaction;
import com.shadowfit.model.group.ReactionKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface EventReactionRepository extends JpaRepository<EventReaction, Long> {

    /** (이벤트, 종류)별 개수 — 피드 한 페이지의 카운트를 쿼리 하나로. UNIQUE 선두(event_id)를 탄다. */
    record KindCount(Long eventId, ReactionKind kind, long count) {}

    @Query("select new com.shadowfit.repository.group.EventReactionRepository$KindCount(r.event.id, r.kind, count(r)) "
         + "from EventReaction r where r.event.id in :eventIds group by r.event.id, r.kind")
    List<KindCount> countByEventIds(@Param("eventIds") Collection<Long> eventIds);

    /** 요청자가 누른 것 — 피드 응답의 {@code myReactions}. */
    record MemberKind(Long eventId, ReactionKind kind) {}

    @Query("select new com.shadowfit.repository.group.EventReactionRepository$MemberKind(r.event.id, r.kind) "
         + "from EventReaction r where r.event.id in :eventIds and r.member.id = :memberId")
    List<MemberKind> findKindsByEventIdsAndMemberId(@Param("eventIds") Collection<Long> eventIds,
                                                    @Param("memberId") Long memberId);

    // PUT 멱등의 흔한 경로 — 있으면 INSERT 를 시도조차 안 한다. 그 틈의 더블탭은 UNIQUE 가 막는다.
    boolean existsByEventIdAndMemberIdAndKind(Long eventId, Long memberId, ReactionKind kind);

    // DELETE 멱등 — 없으면 0 행, 있으면 1 행. 조회 없이 한 문장으로.
    @Modifying
    @Query("delete from EventReaction r where r.event.id = :eventId and r.member.id = :memberId and r.kind = :kind")
    int deleteByEventIdAndMemberIdAndKind(@Param("eventId") Long eventId, @Param("memberId") Long memberId,
                                          @Param("kind") ReactionKind kind);
}
