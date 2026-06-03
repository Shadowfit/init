#!/usr/bin/env bash
# 1억 행 합성 시딩 — pose_data_scale (RealMySQL 실험 §3 선결, docs/portfolio/realmysql-experiments.md)
#
# 133,334 세션 × 750행(끝세션 250) = 정확히 1억 행, 더미 JSON {}, 2026년 12개월+ 분산.
# 행수·payload 디커플링(§0.3): 실제 2.3KB JSON이면 255GB라 로컬 불가 → 더미로 ~11GB.
# 전제: docker shadowfit-mysql 가동, 버퍼풀 2GB·sort_buffer 64M (docker-compose.yml command).
#   ⚠️ 기본 버퍼풀 128MB로 돌리면 롤백/풀스캔이 디스크 random I/O로 붕괴(롤백 64행/s) — 반드시 2GB.
set -u
PW=1234
DB(){ docker exec shadowfit-mysql mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }

echo "[1/5] 테이블 생성 (인덱스 없이 — random insert 페이지 분할 회피, 시딩 가속)"
DB -e "
DROP TABLE IF EXISTS pose_data_scale;
CREATE TABLE pose_data_scale (
  id bigint NOT NULL AUTO_INCREMENT,
  session_id bigint NOT NULL,
  timestamp_sec double NOT NULL,
  joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL,
  sync_rate double DEFAULT NULL,
  is_correct tinyint(1) DEFAULT 1,
  feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, created_at)            -- created_at 포함: ②d Range 파티션 선결
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"

echo "[2/5] numbers 테이블 _seq (0~99만, digit cross join)"
DB -e "
DROP TABLE IF EXISTS _seq;
CREATE TABLE _seq (n INT PRIMARY KEY);
INSERT INTO _seq
WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
SELECT d0.n+d1.n*10+d2.n*100+d3.n*1000+d4.n*10000+d5.n*100000 FROM d d0,d d1,d d2,d d3,d d4,d d5;"

echo "[3/5] 청크 시딩 (세션 5000씩, flush 완화). created_at = 세션 n*4분 분산"
DB -e "SET GLOBAL innodb_flush_log_at_trx_commit=2;"   # 시딩 한정 완화 — 끝에 복구
seed_chunk(){  # $1=세션start $2=세션end(exclusive) $3=세션당 행수
  DB -e "INSERT INTO pose_data_scale (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message, created_at)
  SELECT s.n+1, r.n*1.2, '{}', 75.0, 1, 'ok',
         TIMESTAMP('2026-01-01 06:00:00') + INTERVAL (s.n*4) MINUTE + INTERVAL FLOOR(r.n*1.2) SECOND
  FROM _seq s CROSS JOIN _seq r WHERE s.n >= $1 AND s.n < $2 AND r.n < $3;"
}
# 순차 ~50분. ⚡가속: 아래 루프를 세션범위 3분할해 백그라운드 동시 실행하면 ~16분
#   (예: seq 0 8 / 9 17 / 18 26 을 각각 `&`로 — 실제 측정 시 그렇게 했음)
for i in $(seq 0 26); do
  s=$((i*5000)); e=$(((i+1)*5000)); [ $e -gt 133333 ] && e=133333
  [ $s -ge 133333 ] && break
  seed_chunk $s $e 750
  echo "  청크 세션 $s~$e"
done
seed_chunk 133333 133334 250   # 끝세션 250행 → 99,999,750 + 250 = 정확히 1억

echo "[4/5] 인덱스 일괄 빌드 (sort_buffer 64M) + flush 복구"
DB -e "
CREATE INDEX idx_session_timestamp ON pose_data_scale (session_id, timestamp_sec);
SET GLOBAL innodb_flush_log_at_trx_commit=1;"

echo "[5/5] ANALYZE + _seq 정리 + 검증"
DB -e "ANALYZE TABLE pose_data_scale; DROP TABLE IF EXISTS _seq;"
DB -t -e "SELECT COUNT(*) total_rows, COUNT(DISTINCT session_id) sessions,
                 MIN(created_at) lo, MAX(created_at) hi FROM pose_data_scale;"
echo "DONE — 기대값: total_rows=100000000, sessions=133334"
