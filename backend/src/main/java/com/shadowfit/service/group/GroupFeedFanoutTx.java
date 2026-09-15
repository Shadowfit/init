package com.shadowfit.service.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 세션 완료 자동 글의 팬아웃 — 회원의 ACTIVE 그룹 <b>전부를 한 트랜잭션</b>에 발행한다
 * (social-cheer-and-group-feed.md §4-4 ③ b).
 *
 * <p>그룹마다 따로 커밋하면 중간 실패 시 앞 그룹엔 글이 있고 뒤엔 없는 채로 RETRY 가 돌아 앞 그룹에 글이
 * 두 번 생긴다. 한 트랜잭션이면 아웃박스 행 하나의 결과가 원자적이라 RETRY 가 부분 중복을 못 만든다.
 *
 * <p><b>잠금 순서 규약</b> — {@code GroupEventService.publish} 는 그룹 행을 {@code PESSIMISTIC_WRITE} 로 잠근다.
 * 한 트랜잭션이 그룹을 둘 이상 잠그는 곳은 여기가 처음이므로, 두 팬아웃이 같은 그룹 집합을 서로 반대
 * 순서로 잠그면 데드락이다. 항상 <b>group_id 오름차순</b>으로 잠근다 — 다른 경로(가입·소켓 발행)는 그룹
 * 1개만 잠그므로 오름차순이면 순환이 생길 수 없다.
 *
 * <p>별도 빈인 이유는 {@code SessionCompletionTx} 와 같다(#175) — 예외를 {@code DispatchOutcome} 으로 옮기는
 * 쪽({@code SessionCompletedFeedService})이 트랜잭션 <b>밖</b>이어야 롤백이 끝난 뒤 판정할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupFeedFanoutTx {

    private final SessionRepository sessionRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupEventRepository groupEventRepository;
    private final GroupEventService groupEventService;
    private final ObjectMapper objectMapper;

    /** 팬아웃 결과 — 발행기가 {@code DispatchOutcome} 으로 접는 재료. */
    public enum Result {
        /** 세션이 없다(적재 뒤 삭제). 재시도해도 같다. */
        NO_SESSION,
        /** ACTIVE 그룹이 하나도 없다(적재 뒤 전부 탈퇴). 재시도해도 같다. */
        NO_GROUPS,
        /** 그룹마다 글이 있다 — 이번에 만들었든 이미 있었든. */
        PUBLISHED
    }

    @Transactional
    public Result publishSessionCompleted(Long sessionId) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return Result.NO_SESSION;
        }
        Long memberId = session.getMember().getId();

        // 이 조회와 publish() 안의 ACTIVE 재검사는 같은 트랜잭션·같은 스냅샷이라 서로 어긋나지 않는다 —
        // 그새 탈퇴한 회원의 그룹은 여기서부터 목록에 없다(§4-4 ⑤ 의 «건너뛴다» 가 실현되는 자리).
        // 스냅샷 뒤에 탈퇴하면 글이 하나 남는데, 그건 «완료 시점엔 멤버였다» 는 사실과 어긋나지 않는다.
        List<GroupMember> memberships = groupMemberRepository
                .findAllByMemberIdAndStatusOrderByGroupIdAsc(memberId, GroupMemberStatus.ACTIVE);
        if (memberships.isEmpty()) {
            return Result.NO_GROUPS;
        }

        String payload = payload(session);
        int published = 0;
        for (GroupMember membership : memberships) {
            Long groupId = membership.getGroup().getId();
            // 회수분(lease 상실 뒤 다른 발행기가 집어 간 행)이 다시 오면 여기서 걸러진다(§4-4 ④ c). 동시 재발행이
            // 이 검사를 둘 다 통과하면 UNIQUE(V19) 위반으로 트랜잭션 전체가 RETRY 로 빠지고, 다음 시도가 걸러낸다.
            if (groupEventRepository.existsByGroupIdAndEventTypeAndSourceId(
                    groupId, GroupEventTypes.SESSION_COMPLETED, sessionId)) {
                continue;
            }
            groupEventService.publish(groupId, memberId, GroupEventTypes.SESSION_COMPLETED, payload, sessionId);
            published++;
        }
        log.debug("세션 완료 자동 글 발행 — sessionId: {}, 그룹 {}개 중 {}개 신규", sessionId, memberships.size(), published);
        return Result.PUBLISHED;
    }

    /**
     * §4-4 ① a — §3-G 가 «같은 모임에 공개» 로 정한 항목 밖의 것(rep 수·칼로리·싱크로율)은 싣지 않는다.
     * 문구는 프론트 몫(«{username}님이 {exerciseName}을 완료했어요»).
     */
    private String payload(Session session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sessionId", session.getId());
        node.put("memberId", session.getMember().getId());
        node.put("username", session.getMember().getUsername());
        node.put("exerciseName", session.getExercise().getName());
        return node.toString();
    }
}
