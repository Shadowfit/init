-- 모임 피드 리액션 — docs/decisions/social-cheer-and-group-feed.md §3-D(b) · §4-5 (2026-09-14 사용자 confirm)
--
-- 대상은 group_events 행(세션 완료 자동 글 등 — 타입 제한 없음, §4-5 ⑤). 회원이 같은 글에 💗🔥 둘 다
-- 달 수 있으므로 UNIQUE 는 (event_id, member_id, kind) 다. 카운트는 COUNT(*) — 12명 규모에 denormalize
-- 근거 없음(§3-D).
--
-- kind — Java enum ReactionKind(HEART|FIRE) 와 짝인 VARCHAR. DB ENUM 을 안 쓰는 이유는 notifications.type 과
--   같다(V16 이 지운 report_type 의 함정 — 값 추가마다 ALTER).
--
-- 인덱스 — 피드 한 페이지의 카운트(GROUP BY event_id, kind)와 «내 리액션»(event_id IN … AND member_id = ?)
--   둘 다 UNIQUE 의 선두 event_id 를 탄다. member_id 단독 조회 경로는 없고 FK 가 암묵 인덱스를 만들므로
--   따로 두지 않는다.
--
-- FK — 글이 지워지면(그룹 삭제 CASCADE) 리액션도 함께, 회원 탈퇴도 CASCADE. 그룹 탈퇴(LEFT)는 행을 안
--   지운다 — 글도 남듯이(§4-5 ⑧).
-- 멱등 쓰기(PUT) 의 더블탭은 이 UNIQUE 가 막고, 위반은 «이미 있다» 로 해석한다(push_tokens 선례).
CREATE TABLE event_reactions (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id   BIGINT      NOT NULL COMMENT '대상 group_events.id',
    member_id  BIGINT      NOT NULL COMMENT '누른 회원',
    kind       VARCHAR(20) NOT NULL COMMENT 'ReactionKind (HEART|FIRE)',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_reactions_event_member_kind (event_id, member_id, kind),
    CONSTRAINT fk_event_reactions_event  FOREIGN KEY (event_id)  REFERENCES group_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_reactions_member FOREIGN KEY (member_id) REFERENCES users(id)        ON DELETE CASCADE
);
