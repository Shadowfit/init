package com.shadowfit.service.group;

import com.shadowfit.model.outbox.DispatchOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 아웃박스 {@code SESSION_COMPLETED} 행 하나를 모임 자동 글로 옮기고 결과를 {@link DispatchOutcome} 으로 접는다
 * (social-cheer-and-group-feed.md §4-4 ⑥). 상대가 바깥이 아니라 같은 DB 라 «송신» 이 곧 트랜잭션 하나다 —
 * 그래서 {@code PushDispatchService} 와 달리 부분 성공 분류가 없다.
 *
 * <ul>
 *   <li>세션 없음(적재 뒤 삭제) → TERMINAL_FAILED</li>
 *   <li>ACTIVE 그룹 0개(적재 뒤 전부 탈퇴) → TERMINAL_FAILED — 대상 없음은 재시도해도 같다</li>
 *   <li>발행 트랜잭션 예외(락 대기·데드락·UNIQUE 경합·DB) → RETRY — 전부 롤백됐으므로 다음 시도가 처음부터</li>
 *   <li>그 외 → SENT</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCompletedFeedService {

    private final GroupFeedFanoutTx fanoutTx;

    public DispatchOutcome dispatch(Long sessionId) {
        GroupFeedFanoutTx.Result result;
        try {
            result = fanoutTx.publishSessionCompleted(sessionId);
        } catch (DataAccessException e) {
            // 락 대기 초과·데드락 희생·동시 재발행의 UNIQUE 위반 — 트랜잭션은 이미 롤백됐다. 다음 시도가
            // exists 로 걸러내며 처음부터 다시 하므로 부분 결과가 남지 않는다.
            log.warn("자동 글 발행 실패 — 재시도 대상 (sessionId: {}): {}", sessionId, e.getMessage());
            return DispatchOutcome.RETRY;
        }
        return switch (result) {
            case NO_SESSION -> {
                log.warn("자동 글 대상 세션이 없음(적재 뒤 삭제) — sessionId: {}", sessionId);
                yield DispatchOutcome.TERMINAL_FAILED;
            }
            case NO_GROUPS -> {
                log.warn("자동 글을 받을 모임이 없음(적재 뒤 전부 탈퇴) — sessionId: {}", sessionId);
                yield DispatchOutcome.TERMINAL_FAILED;
            }
            case PUBLISHED -> DispatchOutcome.SENT;
        };
    }
}
