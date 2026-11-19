-- 피드 글 응원 — 공유 글 아래 «채린 😝 다음에 같이 운동하자» 한 줄 (2026-10-06, 프론트 응원 모달 디자인).
--
-- 리액션(event_reactions, HEART·FIRE 카운트)과 다른 표인 이유: 리액션은 «몇 명이 눌렀나» 를 세는 것이고,
-- 응원은 «누가 무슨 말을 했나» 를 보여주는 것이다. 카운트 쿼리에 본문 컬럼을 섞지 않는다.
--
-- UNIQUE(event_id, member_id) — 한 글에 회원당 응원 한 줄. 다시 보내면 그 줄을 바꾼다(PUT 멱등).
--   글마다 댓글이 쌓이는 구조가 아니라 «내 응원 한마디» 라서다. 지우는 건 DELETE.
--
-- FK — 글이 지워지면(그룹 삭제 CASCADE 로 group_events 가 사라지면) 응원도 같이. 회원 탈퇴도 CASCADE
--   (리액션과 같은 판단 — 응원 한 줄은 쓴 사람의 것).
CREATE TABLE event_cheers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL COMMENT 'group_events.id',
    member_id BIGINT NOT NULL COMMENT '응원한 회원',
    message VARCHAR(100) NOT NULL COMMENT '정형 문구 또는 직접 입력',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_cheers_event_member (event_id, member_id),
    CONSTRAINT fk_event_cheers_event FOREIGN KEY (event_id) REFERENCES group_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_cheers_member FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
);
