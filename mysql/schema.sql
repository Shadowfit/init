-- 인코딩 강제 (한글 깨짐 방지). 클라이언트 charset이 latin1 이어도 utf8mb4 로 협상.
SET NAMES utf8mb4;

-- 1. 데이터베이스 생성 및 선택
CREATE DATABASE IF NOT EXISTS shadowfit;
USE shadowfit;

-- 2. 사용자 테이블 (Member)
CREATE TABLE IF NOT EXISTS users (
                                     id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                     email VARCHAR(100) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    username VARCHAR(50) UNIQUE NOT NULL,
    sex ENUM('MALE', 'FEMALE', 'NONE') DEFAULT 'NONE',
    role VARCHAR(20) DEFAULT 'USER', -- UserRole enum(USER/ADMIN)의 실제 EnumType.STRING 값과 일치 (2026-07-15 정정, 기존 'ROLE_USER'는 한 번도 안 쓰이던 값)
    profile_image_url VARCHAR(500),
    height DECIMAL(5,1),
    weight DECIMAL(5,1),
    workout_level VARCHAR(20),
    selected_persona ENUM('BEGINNER', 'ADVANCED', 'DIET', 'REHAB') NOT NULL DEFAULT 'BEGINNER',
    preferred_url VARCHAR(500),
    onboarding_completed BOOLEAN DEFAULT FALSE,
    tts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tts_speed DECIMAL(3,1) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DATETIME 대신 TIMESTAMP 권장 (타임존 대응)
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP -- 자동 갱신 설정
    );

-- 2-1. 리프레시 토큰 (RefreshToken.java — 기존에 ddl-auto=update로 암묵 생성되던 테이블, 2026-07-15 명시화)
CREATE TABLE IF NOT EXISTS refresh_token (
                                     member_id BIGINT PRIMARY KEY,
                                     token VARCHAR(512) NOT NULL,
                                     FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
    );

-- 3. 운동 종목 마스터
CREATE TABLE IF NOT EXISTS exercises (
                                         id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                         name VARCHAR(100) NOT NULL,
    category ENUM('LOWER', 'BACK', 'UPPER', 'CORE', 'FULL') NOT NULL,
    description TEXT,
    preferred_url VARCHAR(500),
    target_joints JSON,
    sync_threshold_beginner DECIMAL(5,2) DEFAULT 60.00,
    sync_threshold_advanced DECIMAL(5,2) DEFAULT 85.00,
    sync_threshold_diet DECIMAL(5,2) DEFAULT 70.00,
    sync_threshold_rehab DECIMAL(5,2) DEFAULT 50.00,
    expected_duration_minutes INT DEFAULT 15,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP -- DEFAULT 추가
    );

-- 4. 운동별 기준 자세 데이터
CREATE TABLE IF NOT EXISTS exercise_references (
                                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                   exercise_id BIGINT NOT NULL,
                                                   timestamp_sec DECIMAL(10,3) NOT NULL,
    joint_coordinates JSON NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DEFAULT 추가
    FOREIGN KEY (exercise_id) REFERENCES exercises(id) ON DELETE CASCADE,
    INDEX idx_exercise_ref_id (exercise_id)
    );

-- 5. 운동 세션
CREATE TABLE IF NOT EXISTS exercise_sessions (
                                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                 member_id BIGINT NOT NULL,
                                                 exercise_id BIGINT NOT NULL,
                                                 reference_source VARCHAR(500),
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    total_reps INT DEFAULT 0,
    avg_sync_rate DECIMAL(5,2),
    max_sync_rate DECIMAL(5,2),
    min_sync_rate DECIMAL(5,2),
    calories_burned DECIMAL(7,2),
    difficulty_level INT DEFAULT 1,
    status ENUM('IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED') DEFAULT 'IN_PROGRESS',
    version BIGINT NOT NULL DEFAULT 0, -- 낙관적 락 (JPA @Version)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DEFAULT 추가
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id),
    -- 캘린더/주간활동 조회(member_id + start_time 범위)가 FK 단일 인덱스로는
    -- member_id로 찾은 뒤 range를 filesort/filter 하는 게 EXPLAIN으로 확인돼 추가
    -- (report-read-path.md §4 인덱스 갭 ④, production-signal-checklist.md §2-2 관련 조사)
    INDEX idx_session_member_starttime (member_id, start_time),
    -- 직전 동일 운동 조회(findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc, 이전 기록
    -- 비교용)가 위 인덱스만으론 member_id로 찾은 뒤 exercise_id·status를 filter(Using where,
    -- filtered 5.19%)하는 게 EXPLAIN으로 확인돼 추가 (2026-07-15, filtered 100%로 개선)
    INDEX idx_session_member_exercise_status_start (member_id, exercise_id, status, start_time),
    -- 회원당 활성 세션 체크(existsByMemberIdAndStatus, createSession 매 호출마다 실행)가 위
    -- 인덱스로는 exercise_id가 중간에 껴서 status까지 seek 못 하고 member_id로 찾은 뒤 status를
    -- filter(filtered 10%, rows 1675)하는 게 EXPLAIN으로 확인돼 추가 — (member_id, status)만으로
    -- 바로 seek해 rows 1, filtered 100%로 개선 (2026-07-16).
    INDEX idx_session_member_status (member_id, status)
    );

-- 6. 자세 데이터
-- pose_data: 날짜 파티셔닝 적용 (TTL 만료 시 DROP PARTITION이 DELETE보다 ~625배 빠름,
-- 실측: loadtest/measure_partition.sh, docs/portfolio/realmysql-experiments.md).
-- MySQL/InnoDB는 FK 걸린 테이블의 파티셔닝을 지원 안 해서(ERROR 1506) 아래 두 가지를 함께 변경:
--   1) FK(session_id → exercise_sessions, ON DELETE CASCADE) 제거
--      → 참조무결성은 애플리케이션이 대체: INSERT 시 세션 존재 검증(PoseDataService.savePoseDataBatch),
--        회원 탈퇴 시 이벤트 트리거 비동기 정리(MemberService.deleteAccount, PoseDataCleanupService)
--      → docs/decisions/pose-data-partition-fk-tradeoff.md 참조
--   2) PK를 id 단일키 → (id, created_at) 복합키로 변경 (파티션 키가 모든 유니크키에 포함돼야 하는 제약)
CREATE TABLE IF NOT EXISTS pose_data (
                                         id BIGINT AUTO_INCREMENT,
                                         session_id BIGINT NOT NULL,
                                         timestamp_sec DECIMAL(10,3) NOT NULL, -- [수정] 소수점 타임스탬프 대응을 위해 DECIMAL로 변경
    joint_coordinates JSON NOT NULL,
    sync_rate DECIMAL(5,2) NOT NULL,
    is_correct BOOLEAN DEFAULT TRUE,
    feedback_message VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    INDEX idx_session_timestamp (session_id, timestamp_sec)
    )
    PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
      PARTITION p2026_01 VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01 00:00:00')),
      PARTITION p2026_02 VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01 00:00:00')),
      PARTITION p2026_03 VALUES LESS THAN (UNIX_TIMESTAMP('2026-04-01 00:00:00')),
      PARTITION p2026_04 VALUES LESS THAN (UNIX_TIMESTAMP('2026-05-01 00:00:00')),
      PARTITION p2026_05 VALUES LESS THAN (UNIX_TIMESTAMP('2026-06-01 00:00:00')),
      PARTITION p2026_06 VALUES LESS THAN (UNIX_TIMESTAMP('2026-07-01 00:00:00')),
      PARTITION p2026_07 VALUES LESS THAN (UNIX_TIMESTAMP('2026-08-01 00:00:00')),
      PARTITION p2026_08 VALUES LESS THAN (UNIX_TIMESTAMP('2026-09-01 00:00:00')),
      PARTITION p2026_09 VALUES LESS THAN (UNIX_TIMESTAMP('2026-10-01 00:00:00')),
      PARTITION p2026_10 VALUES LESS THAN (UNIX_TIMESTAMP('2026-11-01 00:00:00')),
      PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
      PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
      PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
      -- 위 범위를 넘는 미래 데이터는 임시로 이 파티션에 적재됨 — 운영 시 주기적으로
      -- ALTER TABLE ... REORGANIZE PARTITION pfuture INTO (...) 로 월별 파티션을 추가해야 함
      PARTITION pfuture VALUES LESS THAN MAXVALUE
    );

-- 7. 달력 일지
CREATE TABLE IF NOT EXISTS daily_logs (
                                          id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                          member_id BIGINT NOT NULL,
                                          log_date DATE NOT NULL,
                                          memo TEXT,
                                          total_exercise_time INT DEFAULT 0,
                                          total_calories DECIMAL(7,2) DEFAULT 0,
    mood ENUM('GREAT', 'GOOD', 'NORMAL', 'BAD', 'TERRIBLE'),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_member_date (member_id, log_date)
    );

-- 8. 운동 보고서
CREATE TABLE IF NOT EXISTS reports (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       member_id BIGINT NOT NULL,
                                       session_id BIGINT NOT NULL,
                                       report_type ENUM('SESSION', 'WEEKLY', 'MONTHLY') DEFAULT 'SESSION',
    summary TEXT,
    detailed_analysis JSON,
    improvement_tips TEXT,
    comparison_with_previous JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DEFAULT 추가
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE,
    -- 세션당 리포트 1건 보장 (report 생성 멱등성, db-deep-dive.md §C) — 현재 report를 생성하는
    -- 애플리케이션 코드는 없고 시드(data.sql)로만 채워지지만, 추후 생성 로직이 들어올 때
    -- 재시도로 인한 중복 생성을 DB 제약으로 막기 위해 선반영
    UNIQUE KEY uk_report_session (session_id)
    );

-- 9-A. 운동별 피드백 메시지 템플릿 (TTS 멘트, 페르소나별 분기)
-- persona NULL row 는 페르소나 row 없을 때 fallback 으로 사용 (분기 4-A + BE-13)
CREATE TABLE IF NOT EXISTS exercise_feedback_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exercise_id BIGINT NOT NULL,
    feedback_type VARCHAR(30) NOT NULL,
    persona VARCHAR(10) NULL,
    message VARCHAR(200) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id) ON DELETE CASCADE,
    UNIQUE KEY uk_exercise_feedback_persona (exercise_id, feedback_type, persona)
);

-- 9-B. 세션 피드백 판정 이벤트 로그 (AI 가 BT-SET 으로 batch 송신, 멱등성 보장)
CREATE TABLE IF NOT EXISTS session_feedback_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    feedback_type VARCHAR(30) NOT NULL,
    sync_rate_at_trigger DECIMAL(5,2),
    occurred_at DATETIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE,
    -- idx_session_feedback(session_id, occurred_at)는 2026-07-24 제거 — uk_session_event가 앞 2컬럼을
    -- 그대로 포함해 읽기 쪽엔 이득 0(EXPLAIN 확인: findBySessionIdOrderByOccurredAtAsc·GROUP BY
    -- feedback_type 집계 둘 다 옵티마이저가 idx_session_feedback 존재 시에도 uk_session_event만 선택),
    -- batch INSERT 유지비용만 이중 (production-signal-checklist.md:343, loadtest/measure_redundant_index.sh)
    UNIQUE KEY uk_session_event (session_id, occurred_at, feedback_type)
);

-- 10. 신체 변화 기록 (user_id -> member_id 변경)
-- ⚠️ 현재 Entity/Repository/Service 전부 없는 미구현 테이블(production-signal-checklist.md).
-- ON DELETE CASCADE는 2026-07-24 선반영 — MemberService.deleteAccount가 memberRepository.delete()
-- 하나로 회원 삭제를 처리하고 나머지 테이블 정리를 전부 FK CASCADE에 의존하는 구조라, CASCADE 없이
-- 이 테이블에 쓰기 기능이 생기면 refresh_token과 동일한 FK violation 500 버그가 재현됨(8efbca1에서
-- refresh_token 대상으로 실제 발견·수정한 바 있음).
CREATE TABLE body_records (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              member_id BIGINT NOT NULL,          -- 수정 완료
                              record_date DATE NOT NULL,
                              weight DECIMAL(5,1),
                              body_fat_percentage DECIMAL(4,1),
                              muscle_mass DECIMAL(5,1),
                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
                              INDEX idx_member_date (member_id, record_date)
);