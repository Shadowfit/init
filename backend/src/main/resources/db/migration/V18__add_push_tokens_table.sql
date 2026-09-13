-- 푸시 디바이스 토큰 — docs/decisions/social-cheer-and-group-feed.md §3-C(c) 하위 ①(Expo Push)·§4-2 (2026-09-12 사용자 confirm)
--
-- 회원당 기기 여러 개. 발송(#9)은 notifications 행 → outbox → Expo Push 경로이고, 이 표는 그 경로가
-- «어느 기기로» 를 찾는 자리다.
--
-- UNIQUE(token) — (member_id, token) 이 아닌 이유: 한 기기의 토큰은 항상 마지막으로 등록한 계정 것이어야
--   한다. 공용 기기에서 A 의 로그아웃 요청이 유실된 채 B 가 로그인하면, (member_id, token) 구조에선
--   B 의 기기에 A 의 재촉이 간다. 등록은 토큰 기준 upsert 로 소유자를 옮긴다.
--
-- 만료 컬럼·정리 잡 없음 — «N일 미갱신 삭제» 는 근거 없는 임계값이다. 죽은 토큰은 Expo 가
--   DeviceNotRegistered 로 정확히 알려주고 그때 지운다(#9). updated_at 은 기록만.
--
-- token VARCHAR(255) — Expo 가 최대 길이를 문서화하지 않아 repo 의 불투명 외부 문자열 기본값(V15 description).
--   실제 관측 형태는 ExponentPushToken[22자] ≈ 41자.
CREATE TABLE push_tokens (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT       NOT NULL,
    token      VARCHAR(255) NOT NULL COMMENT 'ExponentPushToken[...] / ExpoPushToken[...]',
    platform   VARCHAR(10)  NOT NULL COMMENT 'PushPlatform (IOS|ANDROID). 발송엔 안 쓰고 진단용',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NULL,
    UNIQUE KEY uk_push_tokens_token (token),
    INDEX idx_push_tokens_member (member_id),
    CONSTRAINT fk_push_tokens_member FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
);
