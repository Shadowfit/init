-- 런지 좌우별 깊이 채점을 위해 프레임이 속한 앞다리를 저장한다 (2026-09-27).
--
-- 지금까지 smoothed_knee_angle은 스쿼트에서만 의미가 고정돼 있었다(좌우 평균, 작을수록
-- 깊음). 런지는 좌우 무릎각이 크게 갈리는 운동이라 평균을 쓰면 실제 깊이보다 항상 얕게
-- 보인다 — ai-server가 이 컬럼에 "앞다리(lead) 무릎각"을 보내도록 바뀌었다
-- (app/core/lunge_analyzer.py, PerRepFrame). 그런데 어느 다리인지는 여기 없으면 rep을
-- 좌/우로 나눠 채점할 방법이 없다 — 이 컬럼이 그 정보다.
--
-- NULL 허용: 스쿼트 프레임과 이 배포 이전 프레임은 전부 NULL이다(이 필드 개념이 없음).
-- 구버전 AI(필드 미전송)에서는 proto3 빈 문자열이 오는데, 빈 문자열을 그대로 저장하면
-- "런지인데 판정 전(빈 문자열)"과 "애초에 이 개념이 없음(NULL)"이 안 갈린다 — 저장 계층
-- (PoseDataService)에서 빈 문자열을 NULL로 접어 넣는다.
ALTER TABLE pose_data
    ADD COLUMN lead_leg VARCHAR(5) NULL DEFAULT NULL;
