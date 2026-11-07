# 두 rig(binlog 재생 시간 · 호스트 상실 RPO)이 같이 쓰는 writer — source 해서 쓴다
#
# 트랜잭션 모양은 08-22 복제 rig(repl2_rig.sh:272)과 같다: INSERT p_rows 행 + 세션 행 UPDATE 를 한 트랜잭션.
# 다른 점은 페이로드 하나 — `joint_coordinates` 에 '{}' 대신 **실제 33 랜드마크 JSON(≈2,076B/행)** 을 넣는다
# (loadtest/seed/gen_pose_template.py, 08-13 백업 라운드의 real 무대와 같은 템플릿). binlog 를 «GB» 로 재면
# 페이로드가 행 수를 정하고, 재생 시간은 행·트랜잭션 수를 따라가기 때문이다(2026-11-07 사용자 결정).
#
# 커밋마다 (세션 id, 커밋 뒤 version, 커밋 시각 µs) 한 줄을 클라이언트로 돌려준다 — 호스트 상실 rig 이
# «서버 밖에서 본 커밋 성공» 의 정본으로 쓴다(서버가 사라져도 클라이언트 파일은 남는다).
#
# 필요한 것: q() · PW · WORK · die() 가 호출하는 쪽에 정의돼 있어야 한다.

COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_GEN="$COMMON_DIR/../../seed/gen_pose_template.py"

# $1 = 컨테이너. lab 스키마·pose_data_scale·session_probe 가 이미 있어야 한다.
setup_writer() {
  local c=$1 py
  # 실제로 «도는» 파이썬을 고른다 — 윈도의 python3 는 스토어 안내만 찍는 가짜일 수 있다(로컬 스모크가 거기서 죽었다)
  py=""; local cand; for cand in python3 python; do command -v "$cand" >/dev/null && "$cand" -c 'import sys' >/dev/null 2>&1 && { py=$cand; break; }; done
  [ -n "$py" ] || die "python 이 없다 — 실제 페이로드 템플릿을 만들 수 없다(대체 페이로드로 재지 않는다)"
  "$py" "$TEMPLATE_GEN" --rows 750 --out "$WORK/_pose_template.sql" >/dev/null || die "템플릿 생성 실패"
  docker exec -i "$c" mysql -uroot -p"$PW" lab < "$WORK/_pose_template.sql" 2>/dev/null || die "템플릿 적재 실패"
  q "$c" "
    DROP TABLE IF EXISTS lab._tpl;
    CREATE TABLE lab._tpl (n INT PRIMARY KEY, ts DOUBLE, jc TEXT, fb VARCHAR(255)) ENGINE=InnoDB;
    INSERT INTO lab._tpl
      SELECT ROW_NUMBER() OVER (ORDER BY timestamp_sec) - 1, timestamp_sec, CAST(joint_coordinates AS CHAR),
             NULLIF(feedback_message, '')
      FROM lab._pose_template;
    DROP TABLE lab._pose_template;" || die "_tpl 생성 실패"

  cat > "$WORK/_proc.sql" <<'SQL'
DELIMITER //
DROP PROCEDURE IF EXISTS lab.load_run //
CREATE PROCEDURE lab.load_run(IN p_session BIGINT, IN p_rows INT, IN p_secs INT)
BEGIN
  DECLARE v_end DATETIME(6);
  DECLARE v_off INT DEFAULT 0;
  DECLARE v_ver BIGINT;
  SET v_end = SYSDATE(6) + INTERVAL p_secs SECOND;
  WHILE SYSDATE(6) < v_end DO
    START TRANSACTION;
      INSERT INTO lab.pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)
        SELECT p_session, ts, jc, 75.0, 1, fb FROM lab._tpl WHERE n >= v_off AND n < v_off + p_rows;
      UPDATE lab.session_probe SET version = version + 1, last_active_at = SYSDATE(6) WHERE id = p_session;
      SELECT version INTO v_ver FROM lab.session_probe WHERE id = p_session;
    COMMIT;
    SELECT p_session, v_ver, ROUND(UNIX_TIMESTAMP(SYSDATE(6)) * 1000000);
    SET v_off = (v_off + p_rows) % (750 - p_rows);
  END WHILE;
END //
DELIMITER ;
SQL
  docker exec -i "$c" mysql -uroot -p"$PW" < "$WORK/_proc.sql" 2>/dev/null || die "프로시저 생성 실패"
}
