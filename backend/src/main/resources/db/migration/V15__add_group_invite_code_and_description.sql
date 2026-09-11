-- 모임 코드 참여 + 모임 설명 (docs/decisions/social-cheer-and-group-feed.md §3-F, 2026-09-11 사용자 confirm)
--
-- invite_code — 그룹당 1개 고정, 8자리. 대문자+숫자에서 헷갈리는 글자(0/O, 1/I)를 뺀 32자.
--   32^8 ≈ 1.1×10^12 라 그룹 수가 수만 개여도 충돌은 무시 가능하고, 레퍼런스 화면의
--   «코드로 참여» 입력란이 손 입력을 전제하므로 사람이 읽고 칠 수 있는 길이여야 한다.
--   만료·회전은 두지 않는다 — 레퍼런스에 만료 UI 가 없어 근거 없는 기능이 된다. 유출 시엔
--   그룹장이 재발급한다(POST /groups/{id}/invite-code).
--
-- NOT NULL 인 이유: 「코드가 없는 그룹」이라는 상태를 스키마에 두지 않는다. 운영 DB 가 아직
--   없어(CD 가 배포 호스트 미연결로 막힘, docs/tasks/28-remaining-work-plan.md #4) 기존 행은
--   dev 컨테이너 것뿐이고, 그 DB 는 재생성한다. 백필을 안 넣는 이유도 같다 — SQL 로 만드는
--   코드는 위 32자 규칙을 못 지키거나 id 유도라 추측 가능해진다.
--
-- description — 레퍼런스 1·2번 화면의 그룹명 아래 한 줄("우리 진짜 거북목 되지 말자"). 선택.
ALTER TABLE workout_groups
    ADD COLUMN description VARCHAR(255) NULL COMMENT '모임 소개 한 줄 (선택)',
    ADD COLUMN invite_code CHAR(8) NOT NULL COMMENT '코드 참여용. 그룹당 1개 고정, 혼동 글자 제외 32자 (social-cheer-and-group-feed.md §3-F)',
    ADD UNIQUE KEY uk_workout_groups_invite_code (invite_code);
