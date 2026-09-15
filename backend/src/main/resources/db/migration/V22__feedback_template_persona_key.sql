-- exercise_feedback_templates 의 persona NULL fallback 행이 UNIQUE 에 안 걸리던 것 (#715, 2026-09-14 사용자 confirm ㄴ — 처음엔 V21 이었는데 #758 의 V21 과 번호가 겹쳐 V22 로 옮김)
--
-- uk_exercise_feedback_persona (exercise_id, feedback_type, persona) 는 «종목·결함당 페르소나별 한 줄» 을
-- 뜻하지만, MySQL 은 UNIQUE 인덱스에서 NULL 을 서로 다른 값으로 본다. 그래서 persona IS NULL 인 fallback 행 —
-- 설계상 페르소나 행이 없을 때 쓰라고 둔 그 행 — 만 같은 (exercise_id, feedback_type) 으로 몇 개든 들어갔다.
-- 중복이 생기면 priority ASC 동순위 두 줄이 되어 어느 멘트가 나갈지 비결정적이다.
--
-- NULL 을 비교 가능한 값('')으로 투영한 생성 컬럼을 두고 UNIQUE 를 그쪽에 건다. persona 컬럼 자체는 그대로
-- nullable 이라 읽는 코드(persona IS NULL fallback merge)·enum·시드는 안 바뀐다. VIRTUAL 인 이유: 인덱스에는
-- 값이 실제로 들어가므로 UNIQUE 강제엔 STORED 와 차이가 없고, 16행짜리 마스터 표라 읽을 때 계산하는 비용도 없다.
-- 지금 NULL 행은 0건이라 기존 데이터 충돌은 없다(V2 시드 16행 전부 persona 채워짐).
ALTER TABLE exercise_feedback_templates
    ADD COLUMN persona_key VARCHAR(10) AS (COALESCE(persona, '')) VIRTUAL
        COMMENT 'UNIQUE 용 — persona 의 NULL(공통 fallback)을 빈 문자열로 투영 (#715)' AFTER persona,
    DROP INDEX uk_exercise_feedback_persona,
    ADD UNIQUE KEY uk_exercise_feedback_persona_key (exercise_id, feedback_type, persona_key);
