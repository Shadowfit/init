-- 알림(재촉하기) 저장 — docs/decisions/social-cheer-and-group-feed.md §3-C(c)·§4-2 (2026-09-11·12 사용자 confirm)
--
-- 저장이 원천이고 소켓·푸시는 전달 수단이다(§3-C). 앱이 꺼진 동안의 재촉을 잃지 않는 최소 구조.
--
-- type VARCHAR — DB ENUM 이 아닌 이유: V12 그룹 4테이블·outbox_events 가 전부 VARCHAR + Java enum 이고,
--   V16 이 «존재할 수 없던 enum 값» 때문에 DB ENUM 을 걷어낸 것이 하루 전이다. CHEER 등 값이 늘 때 DDL 이 없다.
--
-- target_date — «같은 사람에게 같은 날 같은 종류 1회» 를 DB 가 막는다(uk_notifications_daily). «하루» 는
--   임의값이 아니라 재촉의 근거인 «오늘 했나»(AttendanceService, 서버 LocalDate)의 판정 단위에서 온 주기다.
--   같은 시계를 써야 «안 했다 → 재촉 → 자정 넘어 다시 가능» 이 일치하므로 서버가 채우고 클라이언트 값은 안 받는다.
--
-- sender_id NULL + SET NULL — group_events.sender_id 와 같은 정책. 탈퇴자의 행은 UNIQUE 에서 빠지지만(NULL 은
--   유일성 비교에서 제외) 이미 보낸 재촉이라 무해하다.
--
-- payload·ref_id 없음 — NUDGE 는 발신자가 곧 내용이다. CHEER·리액션 알림이 생길 때 그 타입이 요구하는 만큼 추가.
--
-- 인덱스 둘 — 목록은 keyset(WHERE recipient_id=? AND id<? ORDER BY id DESC)이라 (recipient_id, id),
--   미읽음 카운트·read-all 은 WHERE recipient_id=? AND read_at IS NULL 이라 (recipient_id, read_at).
--   하나로 합치면(recipient_id, read_at, id) 목록이 filesort 를 탄다.
CREATE TABLE notifications (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT      NOT NULL,
    sender_id    BIGINT      NULL,
    type         VARCHAR(30) NOT NULL COMMENT 'NotificationType (Java enum 이름 그대로)',
    target_date  DATE        NOT NULL COMMENT '서버 LocalDate. 남발 방지 UNIQUE 의 «하루» 단위',
    read_at      DATETIME    NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notifications_daily (sender_id, recipient_id, type, target_date),
    INDEX idx_notifications_recipient_id (recipient_id, id),
    INDEX idx_notifications_recipient_read (recipient_id, read_at),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_sender    FOREIGN KEY (sender_id)    REFERENCES users(id) ON DELETE SET NULL
);
