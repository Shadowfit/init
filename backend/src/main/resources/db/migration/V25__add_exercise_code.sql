-- 종목 코드 — «AI 분석기 키» (docs/decisions/lunge-and-set-backend.md §3-B B-1, 2026-09-22 사용자 confirm).
--
-- 지금까지 종목의 정체는 DB id 였고, 그 id 를 세 곳이 따로 하드코딩했다 — ai-server 의
-- `_EXERCISE_ID_TO_TYPE = {1: "squat"}`(analyzer_registry.py), 프론트의 `exerciseTypeOf()` switch,
-- 그리고 V2 시드의 `REPLACE INTO … (id, …)`. 시드가 다시 매겨지거나 관리자가 종목을 새로 만들면
-- 세 표가 조용히 어긋난다. 이 컬럼은 그 셋이 같은 값을 보게 하는 자리다.
--
-- NULL 허용인 이유 — 코드는 «분석기가 있는 종목» 의 이름표지 모든 종목의 필수 속성이 아니다. 관리자가
-- POST /admin/exercises 로 만든 종목은 분석기가 없으므로 코드도 없다. 그 대신 analysis_supported 를
-- 켜려면 코드가 있어야 한다는 조건이 AdminExerciseService.updateAnalysisSupport 에 하나 더 붙는다
-- (기준 좌표 유무 W012 와 나란히). NOT NULL 로 가면 기존 임의 행에 EX_{id} 같은 근거 없는 값을 채워야
-- 해서 버렸다.
--
-- UNIQUE 인 이유 — 코드 하나가 분석기 하나를 가리킨다. 같은 코드가 두 행이면 AI 가 어느 종목의 기준
-- 좌표·임계값을 써야 하는지 정할 수 없다. NULL 은 MySQL UNIQUE 에서 서로 충돌하지 않으므로 코드 없는
-- 행이 여럿이어도 된다.
--
-- 값 규칙(대문자·숫자·밑줄, 32자)은 DTO 의 @Pattern 이 강제한다. 여기서는 길이만 맞춘다.
ALTER TABLE exercises
    ADD COLUMN code VARCHAR(32) NULL COMMENT 'AI 분석기 키 (SQUAT·LUNGE·…). NULL = 분석기 없는 종목' AFTER name,
    ADD UNIQUE KEY uk_exercises_code (code);

-- V2 시드 3행의 백필. id 로 고르는 이유 — V2 가 id 를 명시해 넣었고(REPLACE INTO … (id, …)), ai-server
-- 레지스트리도 같은 id 를 보고 있어 «1 = 스쿼트» 가 지금 이 순간의 사실이다. name 으로 고르면 관리자가
-- 이름을 고친 뒤 돌아가는 환경에서 0행이 갱신된다. 이미 코드가 있는 행은 건드리지 않는다.
UPDATE exercises SET code = 'SQUAT' WHERE id = 1 AND code IS NULL;
UPDATE exercises SET code = 'LUNGE' WHERE id = 2 AND code IS NULL;
UPDATE exercises SET code = 'PLANK' WHERE id = 3 AND code IS NULL;
