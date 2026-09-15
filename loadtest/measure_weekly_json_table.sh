#!/usr/bin/env bash
# 주간 요약 B층 JSON_TABLE 쿼리(Q2 회차 곡선 · Q3 worst 분포) — 어디에 비용이 있는가
# (docs/decisions/weekly-json-table-query-tuning.md §4)
#
# ── 무엇을 재는가 ────────────────────────────────────────────────────────────
#
#   WeeklySummaryQueryRepositoryImpl 의 두 네이티브 쿼리는 session_reports.detailed_analysis
#   (JSON) 를 JSON_TABLE 로 펼쳐 집계한다. 인덱스를 얹어서 풀리는 모양이 아니라서, 고치기 전에
#   비용이 어느 자리에 있는지부터 갈라야 한다 (문서 §2):
#
#     (a) 조인 순서 — 옵티마이저가 session_reports 를 먼저 읽으면 회원의 **전 기간** 리포트
#         (팬아웃 F)를, exercise_sessions 를 먼저 읽으면 **주간** 세션(W)만 읽는다. 50배 차이.
#     (b) JSON 파싱 — 행당 비용이 문서 크기, 즉 세션당 rep 수 R 에 비례.
#     (c) GROUP BY — derived table 위의 그룹화라 temporary/filesort.
#
#   변수가 셋(F·W·R)이라 격자를 다 채우지 않고 두 축으로 가른다:
#     축 A  F × W (R=30 고정)   → (a) 가 답. F 축 기울기가 양수면 r 드라이빙.
#     축 B  R      (F=50, W=7)  → (b) 가 답. R 축 기울기가 곧 파싱 비용.
#   후보안(ㄴ-1 힌트 · ㄷ generated column · ㄹ 정규화 표)은 축 B 의 셀에서 before/after 로 잰다.
#   ㄴ-3(status 술어 추가)은 스키마 무변경이라 [3/5] 의 쿼리 목록에 넣어 전 셀에서 잰다 — 1차(2026-09-14)
#   결과를 보고 사후에 추가한 것이다(§7-5).
#
# ── 변수와 고정 ──────────────────────────────────────────────────────────────
#
#   ROWS   셀당 리포트 행수(=세션 행수). 쿼리가 만지는 행은 F 또는 W 뿐이라 총 행수는 인덱스
#          깊이·버퍼풀 점유에만 영향한다. 기본 10만 — R=100 이면 JSON 만 ~550MB/셀이라 더 못 키운다.
#   F      회원당 리포트 수 {7, 50, 365}. 365 = DAU 1,000 가정의 1년차 상한(session-index-composition §0).
#   W      대상 주(2025-10-01 ~ 10-08)에 든 세션 수 {3, 7}. 나머지 F−W 는 그 주 **이전** 52주에 흩는다.
#   R      세션당 rep 수 {10, 30, 100}. 실사용 분포 미측정이라 자릿수로 훑는다.
#
#   회원 수 = ROWS / F. 전 회원 동일 팬아웃(계단) — 롱테일이 아니다.
#   status 는 전부 COMPLETED — 리포트는 완료 세션에만 생기므로(SessionCompletionTx).
#   exercise_sessions 인덱스는 V1__baseline.sql 의 4종을 그대로 둔다. session_reports 는
#   PK + uk(session_id) + (member_id) — FK 자동 인덱스와 같은 모양. FK 자체는 만들지 않는다.
#
# ── 이 장치가 대답하지 못하는 것 ─────────────────────────────────────────────
#
#   ⚠️ JSON 값이 균일하다(syncRate 75~94 반복). 파싱 비용은 키/원소 수의 함수라 값과 무관하지만
#      (measure_json.sh 와 같은 논리), ㄷ 의 worst_rep 인덱스 **선택도**는 값 분포에 달려 있어
#      여기서는 못 잰다 — 파싱 제거 효과만 읽는다.
#   ⚠️ 절대 시간은 이 장비의 것이다(2코어 동거). 버림판 1 + REPS 회 중 **최소값을 신호로** 읽고,
#      셀 간 델타와 기울기만 본다. 절대값을 인용할 땐 calib cpu 를 병기한다(§8 규칙).
#   ⚠️ 실사용 트래픽이 없어 ① 관측(digest 순위)은 건너뛴다 — 문서 §4-1.
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PW=1234
CONTAINER=shadowfit-mysql
ROWS=${ROWS:-100000}
IFS=' ' read -r -a FANOUTS <<<"${FANOUTS:-7 50 365}"
IFS=' ' read -r -a WEEKS   <<<"${WEEKS:-3 7}"
IFS=' ' read -r -a RLIST   <<<"${RLIST:-10 30 100}"
R0=${R0:-30}     # 축 A 에서 고정하는 R
F0=${F0:-50}     # 축 B 에서 고정하는 F
W0=${W0:-7}      # 축 B 에서 고정하는 W
REPS=${REPS:-7}  # 버림판 1회 + REPS 회. 최소·중앙값
DB_SUFFIX=${DB_SUFFIX:-}
DB_NAME=shadowfit_wjt${DB_SUFFIX}
CANDIDATES=${CANDIDATES:-1}   # 0 이면 [4/5] 후보안 측정을 건너뛴다(현행만 빨리 볼 때)
WEEK_FROM='2025-10-01 00:00:00'
WEEK_TO='2025-10-08 00:00:00'

cleanup(){
  local rc=$?
  [[ $rc -ne 0 ]] && echo "!! 비정상 종료(exit $rc). DB ${DB_NAME} 는 남긴다(재실행 시 시딩 건너뜀)." >&2
  return 0
}
trap cleanup EXIT

DB(){ docker exec "$CONTAINER" mysql -uroot -p$PW "$@" 2>/dev/null; }
Q(){ DB "$DB_NAME" "$@"; }
QN(){ DB -sN "$DB_NAME" "$@"; }

# EXPLAIN ANALYZE 는 8.0.18+. 없으면 시간을 못 잰다.
VER=$(DB -sN -e "SELECT VERSION();" || true)
[[ -n "$VER" ]] || { echo "!! ${CONTAINER} 에 붙지 못했다 — docker 가 떠 있는가"; exit 1; }
echo "############ 주간 요약 JSON_TABLE 쿼리 — 비용 자리 실측 (MySQL ${VER}) ############"
echo

# ── 셀 목록 ──────────────────────────────────────────────────────────────────
# 축 A: (F, W, R0)  축 B: (F0, W0, R). 겹치는 셀은 한 번만.
CELLS=()
for F in "${FANOUTS[@]}"; do for W in "${WEEKS[@]}"; do
  [[ "$F" -ge "$W" ]] || continue
  CELLS+=("${F},${W},${R0}")
done; done
for R in "${RLIST[@]}"; do
  c="${F0},${W0},${R}"
  [[ " ${CELLS[*]} " == *" ${c} "* ]] || CELLS+=("$c")
done
es(){ echo "es_f$1_w$2"; }
sr(){ echo "sr_f$1_w$2_r$3"; }

# ── [1/5] 시딩 ───────────────────────────────────────────────────────────────
echo "## [1/5] 스크래치 DB ${DB_NAME} — 셀 ${#CELLS[@]}개, 셀당 ${ROWS} 행"
LASTC=${CELLS[-1]}; IFS=',' read -r lf lw lr <<<"$LASTC"
if QN -e "SELECT 1 FROM $(sr "$lf" "$lw" "$lr") LIMIT 1;" >/dev/null 2>&1; then
  echo "   기존 ${DB_NAME} 재사용 (다시 시딩하려면 DROP DATABASE ${DB_NAME} 후 재실행)"
else
  DB -e "DROP DATABASE IF EXISTS ${DB_NAME}; CREATE DATABASE ${DB_NAME} CHARACTER SET utf8mb4;"
  echo "   _seq ${ROWS} 행"
  Q -e "
  CREATE TABLE _seq (n INT PRIMARY KEY);
  INSERT INTO _seq (n)
  SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000
  FROM (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) f;"
  SEQN=$(QN -e "SELECT COUNT(*) FROM _seq;")
  [[ "$SEQN" -ge "$ROWS" ]] || { echo "!! _seq 가 ${SEQN} 행뿐"; exit 1; }

  # repTrend 템플릿 — R 개 원소. 키 이름은 SessionDetailedAnalysis/RepSyncRateDto 의 직렬화 그대로.
  # 값은 75~94 반복(균일). group_concat_max_len 기본 1024 라 같은 세션 안에서 올린다.
  Q -e "CREATE TABLE _tpl (r INT PRIMARY KEY, trend MEDIUMTEXT NOT NULL);"
  for R in "${RLIST[@]}" "$R0"; do
    Q -e "SET SESSION group_concat_max_len = 16777216;
          INSERT IGNORE INTO _tpl
          SELECT ${R}, GROUP_CONCAT(
            CONCAT('{\"repNumber\":', n+1, ',\"syncRate\":', 75 + (n % 20), '.0,\"timeStamp\":\"01:15\"}')
            ORDER BY n SEPARATOR ',')
          FROM _seq WHERE n < ${R};"
  done

  for c in "${CELLS[@]}"; do
    IFS=',' read -r F W R <<<"$c"
    USERS=$(( ROWS / F )); CELLROWS=$(( USERS * F ))   # ROWS 가 F 로 안 나눠떨어지면 팬아웃이 어긋나므로 잘라 쓴다
    ES=$(es "$F" "$W"); SR=$(sr "$F" "$W" "$R")
    if ! QN -e "SELECT 1 FROM ${ES} LIMIT 1;" >/dev/null 2>&1; then
      echo "   ${ES} — 회원 ${USERS} × ${F} 세션 = ${CELLROWS} (주간 ${W})"
      # 스키마·인덱스는 V1__baseline.sql 의 exercise_sessions 와 같다(FK 제외).
      Q -e "
      CREATE TABLE ${ES} (
        id BIGINT PRIMARY KEY,
        member_id BIGINT NOT NULL,
        exercise_id BIGINT NOT NULL,
        start_time DATETIME NOT NULL,
        end_time DATETIME NULL,
        total_reps INT NULL,
        avg_sync_rate DECIMAL(5,2) NULL,
        status ENUM('IN_PROGRESS','COMPLETED','FAILED','CANCELLED') NOT NULL,
        created_at DATETIME NOT NULL,
        INDEX exercise_id (exercise_id),
        INDEX idx_session_member_status_start (member_id, status, start_time),
        INDEX idx_session_member_exercise_status_start (member_id, exercise_id, status, start_time),
        INDEX idx_session_status_starttime (status, start_time),
        INDEX idx_session_starttime_member (start_time, member_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
      # k = n div USERS 가 회원 안의 순번(0..F-1). k < W 는 대상 주 안에 W 등분으로,
      # 나머지는 그 주 이전 52주에 MD5 해시로 흩는다(등차 구조가 member_id 와 종속되지 않게).
      Q -e "
      INSERT INTO ${ES}
        (id, member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status, created_at)
      SELECT n + 1, 1 + (n % ${USERS}), 1,
             st, st + INTERVAL 15 MINUTE, ${R}, 75.00, 'COMPLETED', st + INTERVAL 15 MINUTE
      FROM (
        SELECT n,
               IF(n DIV ${USERS} < ${W},
                  TIMESTAMP('${WEEK_FROM}') + INTERVAL 6 HOUR + INTERVAL ((n DIV ${USERS}) * 10080 DIV ${W}) MINUTE,
                  TIMESTAMP('2024-10-01 06:00:00')
                    + INTERVAL (CONV(SUBSTRING(MD5(CONCAT('ts', n)), 1, 8), 16, 10) % 524160) MINUTE) AS st
        FROM _seq WHERE n < ${CELLROWS}
      ) t;"
    fi
    echo "   ${SR} — R=${R}"
    Q -e "
    CREATE TABLE ${SR} (
      id BIGINT PRIMARY KEY,
      member_id BIGINT NOT NULL,
      session_id BIGINT NOT NULL,
      summary TEXT NULL,
      detailed_analysis JSON NULL,
      improvement_tips TEXT NULL,
      comparison_with_previous JSON NULL,
      created_at TIMESTAMP NULL,
      updated_at DATETIME NULL,
      UNIQUE KEY uk_report_session (session_id),
      INDEX member_id (member_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
    Q -e "
    INSERT INTO ${SR} (id, member_id, session_id, detailed_analysis, created_at)
    SELECT s.id, s.member_id, s.id,
           CONCAT('{\"worstSection\":{\"repNumber\":', 1 + (CONV(SUBSTRING(MD5(CONCAT('wr', s.id)), 1, 8), 16, 10) % ${R}),
                  ',\"exerciseName\":\"스쿼트\",\"timeStamp\":\"01:15\",\"reason\":\"n회차 · 싱크로율 75%\"},',
                  '\"repTrend\":[', t.trend, ']}'),
           s.end_time
    FROM ${ES} s JOIN _tpl t ON t.r = ${R};"
    Q -e "ANALYZE TABLE ${ES}, ${SR};" >/dev/null
  done
fi

# ── [2/5] 시딩 자기검증 ──────────────────────────────────────────────────────
echo
echo "## [2/5] 시딩 자기검증 — F·W·R 이 실제로 그 값인가 (대상 member_id=1)"
FAIL=0
printf "   %-22s %8s %8s %8s %8s %10s %10s\n" "셀" "행" "F실측" "W실측" "R실측" "JSON(B)" "주간전체행"
# 주간전체행 = 전 회원의 그 주 세션 수. (start_time, member_id) 인덱스로 s 를 먼저 읽는 경로의 비용이
# 이 값에 비례하므로, 옵티마이저가 그 경로를 고르는지는 F 뿐 아니라 이 값(=회원수×W)에도 달려 있다.
for c in "${CELLS[@]}"; do
  IFS=',' read -r F W R <<<"$c"
  ES=$(es "$F" "$W"); SR=$(sr "$F" "$W" "$R")
  CELLROWS=$(( (ROWS / F) * F ))
  N=$(QN -e "SELECT COUNT(*) FROM ${SR};")
  FA=$(QN -e "SELECT COUNT(*) FROM ${SR} WHERE member_id=1;")
  WA=$(QN -e "SELECT COUNT(*) FROM ${SR} r JOIN ${ES} s ON s.id=r.session_id
              WHERE r.member_id=1 AND s.start_time>='${WEEK_FROM}' AND s.start_time<'${WEEK_TO}';")
  RA=$(QN -e "SELECT JSON_LENGTH(detailed_analysis,'\$.repTrend') FROM ${SR} WHERE member_id=1 LIMIT 1;")
  JB=$(QN -e "SELECT ROUND(AVG(LENGTH(detailed_analysis))) FROM ${SR} WHERE member_id=1;")
  WT=$(QN -e "SELECT COUNT(*) FROM ${ES} WHERE start_time>='${WEEK_FROM}' AND start_time<'${WEEK_TO}';")
  printf "   %-22s %8s %8s %8s %8s %10s %10s\n" "F=${F} W=${W} R=${R}" "$N" "$FA" "$WA" "$RA" "$JB" "$WT"
  [[ "$N" == "$CELLROWS" && "$FA" == "$F" && "$WA" == "$W" && "$RA" == "$R" ]] || { echo "     !! 불일치"; FAIL=1; }
done
[[ "$FAIL" == "0" ]] || { echo; echo "!! 자기검증 실패 — 측정을 시작하지 않는다."; exit 1; }

# ── 측정 도구 ────────────────────────────────────────────────────────────────
# 시간: EXPLAIN ANALYZE 의 최상위 actual time(버림판 1 + REPS 회, 최소·중앙값).
# 읽은 행: Handler 카운터. tmp/sort: Created_tmp_tables·Sort_rows. 드라이빙: EXPLAIN 첫 table.
# EXPLAIN 의 rows 견적은 쓰지 않는다(admin-page-scope §4-5 의 38배 사례).
run_query(){ # sql -> "minms|medms|handler|tmp|sort|first_table"
  local sql=$1 i ms times=() st
  Q -e "EXPLAIN ANALYZE ${sql}\G" >/dev/null   # 버림판
  for ((i=0;i<REPS;i++)); do
    ms=$(Q -e "EXPLAIN ANALYZE ${sql}\G" | grep -o 'actual time=[0-9.]*\.\.[0-9.]*' | head -1 | sed 's/.*\.\.//')
    times+=("${ms:-NA}")
  done
  local sorted minms medms
  sorted=$(printf '%s\n' "${times[@]}" | sort -g)
  minms=$(echo "$sorted" | head -1)
  medms=$(echo "$sorted" | sed -n "$(( (REPS+1)/2 ))p")
  st=$(QN -e "FLUSH STATUS; ${sql}; SHOW SESSION STATUS WHERE Variable_name IN
        ('Handler_read_key','Handler_read_next','Handler_read_rnd_next','Handler_read_prev',
         'Created_tmp_tables','Sort_rows');" \
      | awk 'NF==2 && $2 ~ /^[0-9]+$/ && $1 ~ /^(Handler_read_|Created_tmp_tables|Sort_rows)/ { if ($1=="Created_tmp_tables") t=$2; else if ($1=="Sort_rows") s=$2; else h+=$2 }
             END {printf "%d|%d|%d", h+0, t+0, s+0}')
  local first; first=$(Q -e "EXPLAIN ${sql}\G" | awk -F': ' '/^ *table:/{t=$2} /^ *key:/{print t "(" $2 ")"; exit}')
  echo "${minms}|${medms}|${st}|${first:-?}"
}
tree(){ # sql -> EXPLAIN FORMAT=TREE, 들여쓰기 유지
  Q -e "EXPLAIN FORMAT=TREE $1\G" | sed -n '/^EXPLAIN:/,$p' | sed 's/^EXPLAIN: //' | sed 's/^/        /'
}
header(){ printf "   %-26s %9s %9s %8s %5s %7s  %s\n" "쿼리" "min(ms)" "med(ms)" "Handler" "tmp" "sort" "드라이빙(인덱스)"; }
row(){ IFS='|' read -r mn md hd tp so ft <<<"$2"; printf "   %-26s %9s %9s %8s %5s %7s  %s\n" "$1" "$mn" "$md" "$hd" "$tp" "$so" "$ft"; }

# 쿼리 — @SR/@ES 는 셀의 표, 회원은 1, 주간 범위는 고정.
JT2="CROSS JOIN JSON_TABLE(r.detailed_analysis, '\$.repTrend[*]' COLUMNS (rep_number INT PATH '\$.repNumber', sync_rate DOUBLE PATH '\$.syncRate')) jt"
JT3="CROSS JOIN JSON_TABLE(r.detailed_analysis, '\$' COLUMNS (worst_rep INT PATH '\$.worstSection.repNumber')) jt"
WK="s.start_time >= '${WEEK_FROM}' AND s.start_time < '${WEEK_TO}'"
q2_cur(){ echo "SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*) FROM $2 r JOIN $1 s ON s.id = r.session_id ${JT2} WHERE r.member_id = 1 AND ${WK} GROUP BY jt.rep_number ORDER BY jt.rep_number"; }
q2_sfirst(){ echo "SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*) FROM $1 s STRAIGHT_JOIN $2 r ON r.session_id = s.id ${JT2} WHERE s.member_id = 1 AND ${WK} GROUP BY jt.rep_number ORDER BY jt.rep_number"; }
# ㄴ-3 — 술어 추가. 리포트는 완료 세션에만 생기므로 status='COMPLETED' 는 결과를 안 바꾸는 중복 술어인데,
# 이게 있어야 (member_id, status, start_time) 세 컬럼이 전부 걸려 완전 범위 스캔이 된다(§7-5). 힌트 없음.
q2_n3(){ echo "SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*) FROM $2 r JOIN $1 s ON s.id = r.session_id ${JT2} WHERE s.member_id = 1 AND s.status = 'COMPLETED' AND ${WK} GROUP BY jt.rep_number ORDER BY jt.rep_number"; }
q3_n3(){ echo "SELECT jt.worst_rep, COUNT(*) FROM $2 r JOIN $1 s ON s.id = r.session_id ${JT3} WHERE s.member_id = 1 AND s.status = 'COMPLETED' AND ${WK} AND jt.worst_rep IS NOT NULL GROUP BY jt.worst_rep ORDER BY COUNT(*) DESC, jt.worst_rep ASC"; }
q3_cur(){ echo "SELECT jt.worst_rep, COUNT(*) FROM $2 r JOIN $1 s ON s.id = r.session_id ${JT3} WHERE r.member_id = 1 AND ${WK} AND jt.worst_rep IS NOT NULL GROUP BY jt.worst_rep ORDER BY COUNT(*) DESC, jt.worst_rep ASC"; }
q3_extract(){ echo "SELECT CAST(r.detailed_analysis->>'\$.worstSection.repNumber' AS UNSIGNED) AS worst_rep, COUNT(*) FROM $2 r JOIN $1 s ON s.id = r.session_id WHERE r.member_id = 1 AND ${WK} AND r.detailed_analysis->>'\$.worstSection.repNumber' IS NOT NULL GROUP BY worst_rep ORDER BY COUNT(*) DESC, worst_rep ASC"; }
q3_gen(){ echo "SELECT r.worst_rep, COUNT(*) FROM $2 r JOIN $1 s ON s.id = r.session_id WHERE r.member_id = 1 AND ${WK} AND r.worst_rep IS NOT NULL GROUP BY r.worst_rep ORDER BY COUNT(*) DESC, r.worst_rep ASC"; }
q2_norm(){ echo "SELECT x.rep_number, AVG(x.sync_rate), COUNT(*) FROM $1 s JOIN $3 x ON x.session_id = s.id WHERE s.member_id = 1 AND ${WK} GROUP BY x.rep_number ORDER BY x.rep_number"; }

# ── [3/5] 현행 — 전 셀 ───────────────────────────────────────────────────────
echo
echo "## [3/5] 현행 쿼리 — 전 셀 (버림판 1 + ${REPS}회, 최소값이 신호)"
echo "   Q2·Q3 = 코드 그대로. s-first = STRAIGHT_JOIN 으로 s 드라이빙 강제(ㄴ-1). ->> = JSON_TABLE 대신 JSON_EXTRACT. +status = ㄴ-3 술어 추가."
i=0
for c in "${CELLS[@]}"; do
  IFS=',' read -r F W R <<<"$c"
  ES=$(es "$F" "$W"); SR=$(sr "$F" "$W" "$R")
  echo
  echo "── F=${F} W=${W} R=${R} ──"
  header
  # 라틴 방격 — 셀마다 쿼리 순서를 한 칸씩 돌려 «순서» 와 «쿼리» 를 분리한다.
  names=("Q2 cur" "Q2 s-first" "Q2 +status" "Q3 cur" "Q3 ->>" "Q3 +status")
  sqls=("$(q2_cur "$ES" "$SR")" "$(q2_sfirst "$ES" "$SR")" "$(q2_n3 "$ES" "$SR")" "$(q3_cur "$ES" "$SR")" "$(q3_extract "$ES" "$SR")" "$(q3_n3 "$ES" "$SR")")
  for ((k=0;k<6;k++)); do
    j=$(( (k + i) % 6 ))
    row "${names[$j]}" "$(run_query "${sqls[$j]}")"
  done
  echo "   EXPLAIN TREE — Q2 cur (JSON_TABLE 이 필터 전인가 후인가):"
  tree "${sqls[0]}"
  echo "   EXPLAIN TREE — Q2 +status (ㄴ-3):"
  tree "${sqls[2]}"
  i=$((i+1))
done

# ── [4/5] 후보안 — 축 B 셀에서 before/after ──────────────────────────────────
if [[ "$CANDIDATES" == "1" ]]; then
  echo
  echo "## [4/5] 후보안 — F=${F0} W=${W0} 셀에서 R 별 before/after"
  echo "   ㄷ = worst_rep STORED generated column + (member_id, worst_rep). ㄹ = rep 단위 표 (session_id, rep_number) PK."
  for R in "${RLIST[@]}"; do
    ES=$(es "$F0" "$W0"); SR=$(sr "$F0" "$W0" "$R"); NR="${SR}_rep"
    echo
    echo "── R=${R} ──"
    # before 를 먼저 잰다 — ㄷ 의 ALTER 는 같은 표의 행을 키우므로(STORED 컬럼) 그 뒤에 재면 before 가 아니다.
    header
    B2=$(run_query "$(q2_cur "$ES" "$SR")"); B3=$(run_query "$(q3_cur "$ES" "$SR")")
    row "Q2 cur (before)" "$B2"
    row "Q3 cur (before)" "$B3"
    # ㄷ — ALTER 시간도 적는다. STORED 추가는 테이블 재빌드라 무중단 DDL 축의 실측이기도 하다.
    if [[ "$(QN -e "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='${SR}' AND COLUMN_NAME='worst_rep';")" == "0" ]]; then
      s=$(date +%s.%N)
      Q -e "ALTER TABLE ${SR} ADD COLUMN worst_rep INT GENERATED ALWAYS AS (detailed_analysis->>'\$.worstSection.repNumber') STORED,
            ADD INDEX idx_member_worst (member_id, worst_rep);"
      e=$(date +%s.%N)
      printf "   ㄷ ALTER(STORED+index) %s 행: %.2fs
" "$ROWS" "$(awk -v a="$e" -v b="$s" 'BEGIN{print a-b}')"
    fi
    # ㄹ — JSON 을 한 번 펼쳐 정규화 표로. 행수 = ROWS × R.
    if ! QN -e "SELECT 1 FROM ${NR} LIMIT 1;" >/dev/null 2>&1; then
      s=$(date +%s.%N)
      Q -e "CREATE TABLE ${NR} (session_id BIGINT NOT NULL, rep_number INT NOT NULL, sync_rate DECIMAL(5,2) NOT NULL,
                                PRIMARY KEY (session_id, rep_number)) ENGINE=InnoDB;
            INSERT INTO ${NR}
            SELECT r.session_id, jt.rep_number, jt.sync_rate FROM ${SR} r ${JT2};"
      e=$(date +%s.%N)
      Q -e "ANALYZE TABLE ${SR}, ${NR};" >/dev/null
      NRN=$(QN -e "SELECT COUNT(*) FROM ${NR};")
      NRMB=$(QN -e "SELECT ROUND((DATA_LENGTH+INDEX_LENGTH)/1024/1024,1) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='${NR}';")
      SRMB=$(QN -e "SELECT ROUND((DATA_LENGTH+INDEX_LENGTH)/1024/1024,1) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='${SR}';")
      printf "   ㄹ 정규화 표 %s 행 적재: %.2fs · 크기 %sMB (JSON 표 %sMB, ㄷ 컬럼 포함)
" "$NRN" "$(awk -v a="$e" -v b="$s" 'BEGIN{print a-b}')" "$NRMB" "$SRMB"
    fi
    Q -e "ANALYZE TABLE ${SR}, ${NR};" >/dev/null
    row "Q2 ㄹ norm (after)" "$(run_query "$(q2_norm "$ES" "$SR" "$NR")")"
    row "Q3 ㄷ gen (after)"  "$(run_query "$(q3_gen "$ES" "$SR")")"
  done
fi

# ── [5/5] 요약 ───────────────────────────────────────────────────────────────
echo
echo "############ 완료 — 읽을 때 주의 ############"
echo "  · (a) 는 [3/5] 의 드라이빙 열과 F 축 기울기로 읽는다. 'Q2 cur' 와 'Q2 s-first' 의 Handler 차이가 그 값이다."
echo "  · (b) 는 [3/5] 축 B(F=${F0} W=${W0}) 의 R 축 기울기, 그리고 [4/5] 의 before/after 델타로 읽는다."
echo "  · 절대 시간은 이 장비의 것이다. 셀 간 델타·기울기만 읽고, 인용 시 calib cpu 를 병기한다."
echo "  · JSON 값은 균일하다. ㄷ 의 인덱스 선택도는 여기서 못 잰다 — 파싱 제거 효과만이다."
