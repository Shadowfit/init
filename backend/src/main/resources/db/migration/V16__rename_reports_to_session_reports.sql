-- reports → session_reports, report_type 삭제 (docs/decisions/redesign-from-scratch-2026-09-11.md B5)
--
-- 왜: reports 는 report_type ENUM('SESSION','WEEKLY','MONTHLY') 을 들고 있었지만 session_id NOT NULL +
--     UNIQUE(session_id) 라 WEEKLY·MONTHLY 행은 애초에 존재할 수 없었다. 주간·월간은 저장하지 않기로
--     이미 결정돼 있어(weekly-monthly-stat-preaggregation.md §0) 그 enum 값은 «약속되지 않은 미래» 였고,
--     실제 행은 전부 'SESSION' 이다. 표 이름이 «세션 리포트» 라고 말하게 하고 컬럼은 지운다.
--
-- 비용: RENAME TABLE 은 메타데이터 변경(즉시). DROP COLUMN 은 8.0.29+ 에서 INSTANT 가 기본이다 —
--       명시하지 않는 이유는 INSTANT 가 불가한 조건(예: 행 포맷)에 걸리면 서버가 INPLACE 로 내려가게
--       두는 편이 «기동 실패» 보다 낫기 때문. 이 표는 세션당 1행이라 어느 쪽이든 짧다.
--       FK(member_id→users, session_id→exercise_sessions)·UNIQUE(uk_report_session)·인덱스는 이름 그대로
--       따라온다 — 제약 이름의 'report' 는 그대로 둔다(이름 바꾸려고 제약을 떼었다 붙이는 건 잠금 대가만 있다).
--
-- 되돌리기: RENAME TABLE session_reports TO reports; ALTER TABLE reports ADD COLUMN report_type
--          ENUM('SESSION','WEEKLY','MONTHLY') DEFAULT 'SESSION'; — 값은 전부 'SESSION' 이었으므로 손실 없음.

RENAME TABLE reports TO session_reports;

ALTER TABLE session_reports
    DROP COLUMN report_type;
