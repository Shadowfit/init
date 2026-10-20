-- 운동 세트 — docs/decisions/lunge-and-set-backend.md §7 (2026-09-22 사용자 confirm, ③단계).
--
-- 세트 경계는 «rep 이 세트당 목표 횟수에 닿는 순간» 하나뿐이다. 그래서 프레임이 몇 세트인지는
-- ceil(rep_number / target_reps_per_set) 로 언제든 역산되고, pose_data(파티션 표)에 set_index 컬럼을 더하지 않는다.
-- 세트별 요약은 세션 완료 시점에 Spring 이 pose_data 의 rep 별 집계에서 만들어 여기에 남긴다 — AI 가 보낸
-- 값이 아니라 저장본에서 집계하는 것은 싱크 통계(#75)와 같은 원칙이다. pose_data 파티션은
-- retention-buffer-months 뒤 드롭되므로, 리포트가 세트를 계속 보여주려면 별도 표가 있어야 한다.

-- 세션당 목표. target_reps_per_set 은 세션 시작 때 body 값 또는 RecommendationService 공식(페르소나·레벨)으로
-- 채워진다. NULL 은 이 컬럼이 생기기 전 세션 — 세트 행이 없고 화면은 «1세트 x N회» 로 폴백한다.
-- target_sets 는 사용자가 정한 값만 들어간다(세트 «수» 는 근거 있는 공식이 없다). NULL = 열린 세트.
ALTER TABLE exercise_sessions
    ADD COLUMN target_reps_per_set INT NULL COMMENT '세트당 목표 횟수. NULL = 세트 도입 전 세션' AFTER difficulty_level,
    ADD COLUMN target_sets INT NULL COMMENT '목표 세트 수 (사용자 입력). NULL = 열린 세트' AFTER target_reps_per_set;

-- 세트별 요약. PK (session_id, set_no) — 세션 안에서 세트 번호는 1부터 빈틈없이 매겨진다.
-- reps 는 마지막 세트만 목표 미달일 수 있다(경계가 목표 도달이므로 중간 세트는 항상 목표와 같다).
-- started_sec/ended_sec 은 pose_data.timestamp_sec 과 같은 원점(첫 프레임 도착 기준 경과 초)이다.
-- avg_sync_rate 는 rep 가중 평균(#75 와 같은 계산), rep 이 없는 세트는 생기지 않으므로 NOT NULL.
-- FK ON DELETE CASCADE — 세션 삭제(DELETE /sessions/{id})와 회원 탈퇴가 세트를 같이 지운다.
CREATE TABLE exercise_session_sets (
    session_id    BIGINT       NOT NULL,
    set_no        INT          NOT NULL COMMENT '1-based',
    reps          INT          NOT NULL,
    avg_sync_rate DECIMAL(5,2) NOT NULL COMMENT 'rep 가중 평균',
    started_sec   DOUBLE       NOT NULL COMMENT '세트 첫 rep 의 첫 프레임 timestamp_sec',
    ended_sec     DOUBLE       NOT NULL COMMENT '세트 마지막 rep 의 마지막 프레임 timestamp_sec',
    PRIMARY KEY (session_id, set_no),
    CONSTRAINT fk_session_sets_session FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE
) COMMENT = '세션별 세트 요약 — 완료 시점에 pose_data 에서 집계';
