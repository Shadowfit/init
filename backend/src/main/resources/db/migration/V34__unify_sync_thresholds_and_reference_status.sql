-- 싱크로율 판정 기준 «하나로 통일» + 기준 좌표 등록 상태 (2026-10-21 사용자 결정 A·B).
--
-- [A] 임계값: 페르소나별 4컬럼(sync_threshold_beginner/advanced/diet/rehab, V1)은 관리자 API 로 바꿀 수만 있고
--     읽는 곳이 하나도 없었다 — AI 는 자체 상수로 판정했고 앱은 값을 몰랐다. «관리자가 바꿨는데 아무 일도 안 일어남»
--     을 없애려고 종목당 한 쌍으로 접는다:
--       sync_good_threshold 이상 = 정석, sync_warn_threshold 이상 = 교정 필요, 그 아래 = 위험.
--     페르소나는 트레이너 말투로만 남는다. 이 한 쌍은 AnalyzeRequest/ReattachRequest(11·12 / 12·13)로 AI 에,
--     세션 시작·재부착·진행 중 세션·리포트 응답으로 앱에 같이 흐른다.
--     옛 값을 옮기지 않는 이유: 어디서도 읽히지 않던 값이라 «지금 동작» 은 AI 기본값(80/60)이었다 — 그것을 그대로 기본값으로 둔다.
--
-- [B] 기준 좌표 등록 상태: 영상 업로드 → AI 비동기 추출의 결과(성공/실패 사유)가 AI 로그에만 남아 관리자 화면은
--     «등록했어요» 로 보이고 기준은 안 바뀌는 일이 있었다. 가장 최근 등록 시도의 결과를 종목 행에 남긴다.
--       reference_extraction_status : EXTRACTING | READY | FAILED. NULL = 한 번도 등록 시도가 없었다(V4 시드 등)
--       reference_extraction_error  : FAILED 사유(관리자에게 그대로 보여준다). 500자에서 자른다
--       reference_extraction_at     : 상태가 바뀐 시각. EXTRACTING 이 10분 넘게 그대로면 조회 때 FAILED 로 보여준다(저장은 안 바꾼다)
ALTER TABLE exercises
    ADD COLUMN sync_good_threshold DECIMAL(5,2) NOT NULL DEFAULT 80.00 COMMENT '이 점수 이상 = 정석',
    ADD COLUMN sync_warn_threshold DECIMAL(5,2) NOT NULL DEFAULT 60.00 COMMENT '이 점수 이상 = 교정 필요, 미만 = 위험',
    DROP COLUMN sync_threshold_beginner,
    DROP COLUMN sync_threshold_advanced,
    DROP COLUMN sync_threshold_diet,
    DROP COLUMN sync_threshold_rehab,
    ADD COLUMN reference_extraction_status VARCHAR(16) NULL COMMENT 'EXTRACTING/READY/FAILED. NULL = 등록 시도 없음',
    ADD COLUMN reference_extraction_error VARCHAR(500) NULL COMMENT 'FAILED 사유',
    ADD COLUMN reference_extraction_at DATETIME NULL COMMENT '등록 상태가 바뀐 시각';
