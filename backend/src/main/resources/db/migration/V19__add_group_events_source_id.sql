-- 세션 완료 자동 글 — docs/decisions/social-cheer-and-group-feed.md §4-4 ④ c (2026-09-14 사용자 confirm)
--
-- group_events 에 «이 글이 어느 원천에서 왔나» 를 담는 자리가 없었다(payload TEXT 뿐). 자동 글은
-- 아웃박스(at-least-once)를 타고 오므로 같은 행이 두 번 발행될 수 있고(lease 상실 뒤 회수), 그때 같은
-- 글이 두 번 생기지 않으려면 (그룹, 타입, 원천) 단위의 키가 필요하다 — AI 쪽은 수신자가 멱등했지만
-- group_events 는 아니었다. payload LIKE 로 찾는 방식은 직렬화 순서가 바뀌면 조용히 깨지므로, 스키마가
-- 원천을 말하고 DB 가 중복을 막게 한다.
--
-- NULL 은 UNIQUE 에서 여러 개 허용되므로 원천이 없는 타입(MEMBER_JOINED, 소켓 클라이언트 발행)은 영향 없다.
-- SESSION_COMPLETED 의 source_id = exercise_sessions.id. FK 는 안 건다 — 세션이 지워져도 글은 남는다
-- (outbox_events.aggregate_id 와 같은 판단).
ALTER TABLE group_events
    ADD COLUMN source_id BIGINT NULL COMMENT '자동 글의 원천 id (SESSION_COMPLETED = exercise_sessions.id). 시스템·클라이언트 이벤트는 NULL' AFTER payload,
    ADD UNIQUE KEY uk_group_events_type_source (group_id, event_type, source_id);
