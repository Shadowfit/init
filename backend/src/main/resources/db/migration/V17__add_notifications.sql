-- 알림 — 재촉하기의 저장 원천 (docs/decisions/social-cheer-and-group-feed.md §3-C c, 2026-09-11 사용자 confirm).
--
-- 저장이 원천이고 소켓(#7)·푸시(#9)는 전달 수단이다 — 앱이 꺼진 동안 온 재촉을 잃지 않는 최소 구조.
--
-- type — Java enum NotificationType 과 짝인 VARCHAR. DB ENUM 을 안 쓰는 이유는 V16 이 지운
--   report_type ENUM 과 같은 결이다: 값이 하나(NUDGE) 뿐인데 목록을 DB 에 박으면 값 추가마다
--   ALTER 가 필요하고, group_events.event_type·outbox_events.event_type 이 이미 VARCHAR 다.
--
-- UNIQUE(sender_id, recipient_id, type, target_date) — «같은 사람에게 같은 날 같은 종류 1회»
--   (§3-C 하위 결정 ②). "하루" 는 임의값이 아니라 재촉의 근거("오늘 했나")가 갱신되는 단위다.
--   존재 확인 뒤 INSERT 라 그 틈의 더블탭은 이 제약이 막고, 위반은 409 로 옮긴다(goals 와 같은 모양).
--   target_date 는 서버 LocalDate — 출석의 날짜 귀속(start_time 의 서버 LocalDate)과 같은 기준.
--
-- FK — 수신자가 탈퇴하면 알림함도 사라지므로 CASCADE. 보낸 사람이 탈퇴해도 받은 사람의 알림은
--   남아야 하므로 SET NULL(group_events.sender_id 와 같은 판단 — 알림은 수신자의 기록이지 둘
--   사이의 관계가 아니다). NULL sender 는 UNIQUE 에 안 걸리지만, 그 시점엔 더 보낼 주체도 없다.
--
-- ref(알림이 가리키는 대상)는 안 만든다 — NUDGE 는 가리킬 것이 없다. 필요한 타입이 생기면
--   nullable ADD COLUMN 으로 충분하다(2026-09-12 사용자 confirm).
CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT NULL COMMENT '보낸 회원. 탈퇴 시 NULL',
    recipient_id BIGINT NOT NULL COMMENT '받는 회원',
    type VARCHAR(50) NOT NULL COMMENT 'NotificationType (NUDGE, ...)',
    target_date DATE NOT NULL COMMENT '하루 1회 판정 단위 (서버 LocalDate)',
    read_at DATETIME NULL COMMENT '읽은 시각. NULL 이면 안 읽음',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notifications_sender_recipient_type_date (sender_id, recipient_id, type, target_date),
    -- 알림함 조회: 수신자의 것을 최신순으로.
    INDEX idx_notifications_recipient_created (recipient_id, created_at),
    CONSTRAINT fk_notifications_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE
);
