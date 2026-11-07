-- «미인정 시도» 기록 (2026-10-21 사용자 결정 — docs/decisions/counted-reps-vs-scored-attempts.md).
--
-- 스쿼트 횟수 판정은 엄격하게 둔다(사람 판정과 맞춘 115°·바닥 두 장 확인). 대신 내려갔다 올라왔지만 1회로 인정되지
-- 않은 시도도 리포트에 보이게 한다 — 예전엔 그런 시도가 AI 안에서 버려져 리포트·DB 어디에도 흔적이 없었고, 그래서
-- «좋은 자세만 세니 리포트는 무조건 좋게 나온다» 였다.
--
--   횟수·세트·목표 달성 : 지금처럼 인정 반복(pose_data)만
--   점수·리포트        : 인정 반복 + 이 표의 시도 — 세션 avg/max/min_sync_rate 에 시도 하나당 값 하나로 들어간다
--
-- AI 가 시도 하나를 «가장 깊이 앉은 순간» 한 장으로 요약해 SaveRejectedAttempt RPC 로 보낸다(프레임 묶음이 아니다).
-- pose_data 에 플래그를 붙이지 않고 표를 따로 둔 이유: ① 구버전 Spring 이 모르는 플래그를 무시하면 미인정 프레임이
-- 정상 반복으로 저장된다(proto 주석) ② pose_data 는 파티션 표라 FK 를 못 걸고 retention 뒤 드롭되는데, 리포트의
-- 미인정 기록은 세션이 사는 동안 남아야 한다 — 세트 표(V26)와 같은 이유다.
--
-- 멱등: AI 가 같은 시도를 재전송해도 행이 늘지 않게 (session_id, attempt_no) UNIQUE + ON DUPLICATE KEY UPDATE.
--   session_id 단독 인덱스는 따로 두지 않는다 — UNIQUE 의 선두 컬럼이라 세션 조회·FK 가 그 키를 쓴다
--   (session_feedback_logs 의 idx_session_feedback 을 같은 이유로 지운 전례, V1 주석).
-- reason 은 VARCHAR 다(ENUM 아님) — 사유가 늘 때 ALTER 없이 받는다. 허용 값 검증은 Spring 입구가 한다.
-- timestamp_sec 은 pose_data.timestamp_sec 과 같은 원점(첫 프레임 도착 기준 경과 초) — 리포트가 두 표를 시간순으로 섞는다.
-- min_knee_angle 이 NULL 이면 «미상»(AI 가 0 을 보냄), torso_tilt_deg 는 pose_data 와 같이 0 도 유효값(완전 직립)이다.
-- FK ON DELETE CASCADE — 세션 삭제(DELETE /sessions/{id})와 회원 탈퇴가 같이 지운다.
CREATE TABLE exercise_rejected_attempts (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
    session_id        BIGINT        NOT NULL,
    attempt_no        INT           NOT NULL COMMENT '세션 안 미인정 시도 번호(1부터) — 재전송 멱등키',
    reason            VARCHAR(32)   NOT NULL COMMENT 'DEPTH_NOT_CONFIRMED | SPLIT_STANCE',
    sync_rate         DECIMAL(5,2)  NOT NULL COMMENT '인정 반복과 같은 식의 싱크로율 0~100',
    min_knee_angle    DECIMAL(5,2)  NULL     COMMENT '가장 깊이 앉은 순간의 무릎각. NULL = 미상',
    torso_tilt_deg    DOUBLE        NULL     COMMENT '그 순간의 체간 기울기(도)',
    timestamp_sec     DECIMAL(10,3) NOT NULL COMMENT 'pose_data.timestamp_sec 과 같은 원점',
    joint_coordinates JSON          NULL     COMMENT '그 순간의 관절 좌표',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rejected_attempt (session_id, attempt_no),
    CONSTRAINT fk_rejected_attempts_session FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE
) COMMENT = '세션별 미인정 시도 — 횟수에는 안 들어가고 점수·리포트에만 들어간다';
