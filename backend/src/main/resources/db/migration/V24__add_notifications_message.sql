-- 알림 본문 — «응원 보내기»(CHEER) 의 메시지 (docs/decisions/social-cheer-and-group-feed.md 결정 로그 2026-09-17).
--
-- 레퍼런스 화면의 «응원 보내기» 모달은 정형 문구(«오늘도 힘내» «칭찬해요!» …) 또는 직접 입력 한 줄을 싣는다.
-- 재촉(NUDGE)은 본문이 없으므로 NULL. 100자 상한은 푸시 본문 한 줄(Expo 가 잘라 보여주는 길이)에서 왔다.
--
-- 타입(CHEER)은 V17 머리 주석대로 VARCHAR 라 여기서 손댈 것이 없다 — UNIQUE(sender, recipient, type, date) 가
-- 그대로 «같은 사람에게 같은 날 재촉 1회 + 응원 1회» 가 된다.
ALTER TABLE notifications
    ADD COLUMN message VARCHAR(100) NULL COMMENT '응원 본문 (CHEER). 재촉(NUDGE)은 NULL' AFTER target_date;
