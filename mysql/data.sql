-- 1. 기존 테이블이 있다면 삭제 (초기화 보장)
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS reports;
DROP TABLE IF EXISTS exercise_sessions;
DROP TABLE IF EXISTS exercises;
DROP TABLE IF EXISTS users;
SET FOREIGN_KEY_CHECKS = 1;


-- 2. 전체 테이블 생성 (AUTO_INCREMENT를 PRIMARY KEY 옆에 바로 붙여야 합니다)
CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                       email VARCHAR(255) UNIQUE NOT NULL,
                       password VARCHAR(255) NOT NULL,
                       username VARCHAR(255),
                       role VARCHAR(20) DEFAULT 'ROLE_USER',
                       selected_persona VARCHAR(50),
                       onboarding_completed BOOLEAN DEFAULT FALSE,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exercises (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                           name VARCHAR(255) NOT NULL,
                           category VARCHAR(50),
                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exercise_sessions (
                                   id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                                   member_id BIGINT,
                                   exercise_id BIGINT,
                                   start_time TIMESTAMP,
                                   end_time TIMESTAMP,
                                   avg_sync_rate DOUBLE,
                                   total_reps INT,
                                   calories_burned INT,
                                   status VARCHAR(20),
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                   FOREIGN KEY (member_id) REFERENCES users(id),
                                   FOREIGN KEY (exercise_id) REFERENCES exercises(id)
);

CREATE TABLE reports (
                         id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                         session_id BIGINT,
                         member_id BIGINT,
                         report_type VARCHAR(20),
                         summary TEXT,
                         improvement_tips TEXT,
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                         FOREIGN KEY (session_id) REFERENCES exercise_sessions(id),
                         FOREIGN KEY (member_id) REFERENCES users(id)
);

-- 3. 데이터 삽입 시작
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 유저 데이터 (ID 1번 확실히 생성)
INSERT INTO users (id, email, password, username, role, selected_persona, onboarding_completed, created_at)
VALUES (1, 'test@test.com', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.TVuHOnu', '효재', 'USER', 'BEGINNER', TRUE, NOW())
    ON DUPLICATE KEY UPDATE id=id;

-- 2. 운동 종목 데이터
INSERT INTO exercises (id, name, category, created_at) VALUES (1, '스쿼트', 'LOWER', NOW()) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO exercises (id, name, category, created_at) VALUES (2, '런지', 'LOWER', NOW()) ON DUPLICATE KEY UPDATE id=id;
INSERT INTO exercises (id, name, category, created_at) VALUES (3, '플랭크', 'CORE', NOW()) ON DUPLICATE KEY UPDATE id=id;

-- 3. 운동 세션 데이터 (여러 행으로 나누어 작성하면 파싱 에러를 줄일 수 있습니다)
INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES (601, 1, 1, '2026-04-01 09:00:00', '2026-04-01 09:30:00', 75.5, 30, 150, 'COMPLETED', NOW());
INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES (602, 1, 2, '2026-04-03 18:00:00', '2026-04-03 18:40:00', 82.0, 40, 210, 'COMPLETED', NOW());
INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES (603, 1, 1, '2026-04-05 10:00:00', '2026-04-05 10:20:00', 88.5, 20, 100, 'COMPLETED', NOW());
INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES (617, 1, 1, '2026-04-25 09:00:00', '2026-04-25 09:20:00', 92.5, 20, 100, 'COMPLETED', NOW());
INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES (618, 1, 2, '2026-04-25 14:00:00', '2026-04-25 14:40:00', 88.0, 40, 190, 'COMPLETED', NOW());
INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES (619, 1, 3, '2026-04-25 20:00:00', '2026-04-25 20:30:00', 95.0, 30, 140, 'COMPLETED', NOW());

-- 4. 리포트 자동 생성
INSERT INTO reports (session_id, member_id, report_type, summary, improvement_tips, created_at)
SELECT id, member_id, 'SESSION', CONCAT(id, '번 리포트'), '안정적입니다.', NOW()
FROM exercise_sessions WHERE id >= 601;

SET FOREIGN_KEY_CHECKS = 1;