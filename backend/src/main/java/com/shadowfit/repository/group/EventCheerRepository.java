package com.shadowfit.repository.group;

import com.shadowfit.model.group.EventCheer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventCheerRepository extends JpaRepository<EventCheer, Long> {

    Optional<EventCheer> findByEventIdAndMemberId(Long eventId, Long memberId);

    /** 피드 한 페이지의 응원 전부 — 글마다 쿼리를 따로 날리지 않는다. 오래된 것부터(화면에 쌓이는 순서). */
    @Query("select c from EventCheer c join fetch c.member where c.event.id in :eventIds order by c.createdAt asc, c.id asc")
    List<EventCheer> findAllByEventIds(@Param("eventIds") Collection<Long> eventIds);

    @Modifying
    @Query("delete from EventCheer c where c.event.id = :eventId and c.member.id = :memberId")
    int deleteByEventIdAndMemberId(@Param("eventId") Long eventId, @Param("memberId") Long memberId);
}
