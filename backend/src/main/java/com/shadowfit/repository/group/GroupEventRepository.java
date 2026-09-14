package com.shadowfit.repository.group;

import com.shadowfit.model.group.GroupEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupEventRepository extends JpaRepository<GroupEvent, Long> {

    // 재연결 백필 — afterSeq 이후 이벤트를 오름차순으로 전부 반환한다(페이지네이션 없음,
    // 근거 없는 limit을 넣지 않기로 한 설계 결정 — 계획 문서 참고).
    List<GroupEvent> findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(Long groupId, Long afterSeq);

    // 자동 글 재발행 멱등성 — «이 원천의 글이 이 그룹에 이미 있나». 아웃박스 회수분이 다시 오면 발행 대신
    // 여기서 걸러진다. 최종 방어선은 uk_group_events_type_source(V19).
    boolean existsByGroupIdAndEventTypeAndSourceId(Long groupId, String eventType, Long sourceId);

    // 피드 — 최신순 keyset(social-cheer-and-group-feed.md §4-5 ②). beforeSeq 미만을 seq 내림차순으로 size 개.
    // 백필과 같은 uk_group_events_group_seq 를 반대 방향으로 탄다.
    List<GroupEvent> findAllByGroupIdAndSeqLessThanOrderBySeqDesc(Long groupId, Long beforeSeq, Pageable pageable);

    // 리액션 대상 — 프론트가 가진 식별자가 (groupId, seq) 라 id 가 아니라 이걸로 찾는다(§4-5 ③).
    Optional<GroupEvent> findByGroupIdAndSeq(Long groupId, Long seq);
}