package com.shadowfit.model.group;

/**
 * 서버가 만드는 그룹 이벤트 타입 — social-cheer-and-group-feed.md §4-4 ⑦.
 *
 * <p>enum 이 아니라 상수인 이유: {@code group_events.event_type} 은 소켓 클라이언트가 임의 문자열을 보내
 * 그대로 발행·릴레이되는 채널이라({@code GroupSocketHandler}) 닫힌 집합이 아니다. 서버 쪽에서 나가는
 * 값만 여기서 한 번 적어, 발행처와 조회처(멱등성 exists)가 같은 철자를 쓰게 한다.
 */
public final class GroupEventTypes {

    /** 가입(초대 수락·코드 참여) — {@code GroupService.admit}. 발신자 없음, 원천 없음. */
    public static final String MEMBER_JOINED = "MEMBER_JOINED";

    /**
     * 세션 완료 자동 글 — 아웃박스 {@code SESSION_COMPLETED} 를 {@code SessionCompletedFeedService} 가
     * 회원의 ACTIVE 그룹마다 하나씩 발행한다. 발신자 = 완료한 회원, 원천 = 세션 id.
     */
    public static final String SESSION_COMPLETED = "SESSION_COMPLETED";

    private GroupEventTypes() {
    }
}
