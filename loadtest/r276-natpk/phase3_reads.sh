#!/usr/bin/env bash
# ③ 읽기 — 지금 표(tpl_A) · 자연키 PK(r_B) · 자연키 PK − idx_session_timestamp(r_Bp)
#
# 설계: docs/decisions/r276-lock-root-cause-fix.md §5-4 ③ — phase2 가 남긴 표를 그대로 쓴다(추가 시딩 없음)
#
#   session  앱의 findFramesBySessionId 와 같은 문장 — 작은 컬럼만, rep_number·timestamp_sec 순
#            A 는 보조 인덱스로 찾아 행마다 PK 를 다시 찾고, B 는 PK 범위를 그대로 읽는다
#   id       앱의 findJointCoordinatesById 와 같은 문장 — A 는 PK 한 번, B 는 idx_id → PK
#
# 차가움 = MySQL 재기동 직후(버퍼풀 비움 — compose 가 덤프·로드를 껐다). DROP_CACHES=1 이면 OS 페이지
# 캐시도 비운다(EC2 에서 root 일 때만 — 로컬 Docker Desktop 에선 못 한다). 데움 = 같은 표본을 바로 한 번 더.
# 판 순서는 라운드마다 표 순서를 돌린다(A B Bp → B Bp A → Bp A B).
set -uo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

N=${N:-300}
ROUNDS=${ROUNDS:-3}
DROP_CACHES=${DROP_CACHES:-0}
OUT=${OUT:-$HERE/_out/phase3}
mkdir -p "$OUT" "$HERE/_work"

log(){ printf '[phase3 %s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log" >&2; }
die(){ log "🔴 중단 — $*"; exit 1; }
SQL(){ docker exec -i natpk_mysql mysql -uroot -pnatpk natpk -N -s -e "$1" 2>/dev/null; }
PY(){ docker exec -i natpk_client python /work/natpk.py "$@"; }

wait_mysql(){
  local hits=0
  for _ in $(seq 1 120); do
    if SQL "SELECT 1" | grep -q 1; then hits=$((hits+1)); [ $hits -ge 2 ] && return 0; else hits=0; fi
    sleep 3
  done
  die "MySQL 이 6분 안에 준비되지 않았다"
}
cold(){
  docker restart natpk_mysql >/dev/null || die "재기동 실패"
  if [ "$DROP_CACHES" = 1 ]; then sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || log "  ⚠️ 페이지 캐시를 못 비웠다(root 아님)"; fi
  wait_mysql
  local d; d=$(SQL "SELECT variable_value FROM performance_schema.global_status WHERE variable_name='Innodb_buffer_pool_pages_data'")
  echo "$d"
}

SQL "SELECT 1" | grep -q 1 || die "MySQL 이 안 떠 있다 — phase2 다음에 돌릴 것"
for t in tpl_A r_B; do SQL "SELECT 1 FROM $t LIMIT 1" | grep -q 1 || die "$t 가 없다 — phase2 를 먼저"; done
# 🔴 r_B 가 정말 phase2 의 결과인지 — 자연키 PK 이고 tpl_A 의 행을 다 갖고 있어야 한다
pk=$(SQL "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
          WHERE table_schema='natpk' AND table_name='r_B' AND index_name='PRIMARY'")
[ "$pk" = "session_id,rep_number,timestamp_sec,created_at" ] || die "r_B 의 PK 가 자연키가 아니다: $pk"
miss=$(SQL "SELECT COUNT(*) FROM tpl_A a LEFT JOIN r_B b ON b.session_id=a.session_id AND b.rep_number=a.rep_number
            AND b.timestamp_sec=a.timestamp_sec AND b.created_at=a.created_at WHERE b.session_id IS NULL")
[ "$miss" = "0" ] || die "r_B 에 tpl_A 의 행 $miss 개가 없다"

if ! SQL "SELECT 1 FROM r_Bp LIMIT 1" | grep -q 1; then
  log "r_Bp 만들기 — r_B 에서 idx_session_timestamp 를 뺀 복사본"
  SQL "CREATE TABLE r_Bp LIKE r_B; ALTER TABLE r_Bp DROP INDEX idx_session_timestamp; INSERT INTO r_Bp SELECT * FROM r_B;" \
    || die "r_Bp 생성 실패"
fi
for t in tpl_A r_B r_Bp; do PY stats "$t" >> "$OUT/stats.jsonl"; done

PY sample tpl_A "$N" /work/out/sample.tsv || die "표본 실패"
cp "$HERE/_work/sample.tsv" "$OUT/"
log "표본 $(wc -l < "$HERE/_work/sample.tsv") 개 (세션·id 쌍)"

TABLES=(tpl_A r_B r_Bp)
for ((r=0;r<ROUNDS;r++)); do
  for mode in session id; do
    for ((k=0;k<3;k++)); do
      t=${TABLES[$(((k+r)%3))]}
      pages=$(cold)
      c=$(PY reads "$t" /work/out/sample.tsv "$mode")
      w=$(PY reads "$t" /work/out/sample.tsv "$mode")
      echo "{\"round\":$r,\"mode\":\"$mode\",\"table\":\"$t\",\"pages_after_restart\":${pages:-null},\"cold\":$c,\"warm\":$w}" >> "$OUT/reads.jsonl"
      log "  r$r $mode $t — 차가움 $(echo "$c" | grep -o '"p50_ms": [0-9.]*') · 데움 $(echo "$w" | grep -o '"p50_ms": [0-9.]*')"
    done
  done
done
log "완료 → $OUT/reads.jsonl"
