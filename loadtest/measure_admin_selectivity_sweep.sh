#!/usr/bin/env bash
# 관리자 세션 목록 — **선택도 × 기간폭 격자**에서 인덱스가 어디까지 듣는가
# (docs/decisions/admin-page-scope.md §4-3 이 "시간은 안 쟀다"로 남긴 칸, 2026-09-10)
#
# ─────────────────────────────────────────────────────────────────────────────
# 왜 이 rig 이 따로 있는가 — measure_admin_index.sh 는 시간을 **일부러** 안 냈다
# ─────────────────────────────────────────────────────────────────────────────
#
#   그 rig 의 헤더가 스스로 못박은 거절 사유는 이렇다:
#
#     "합성 데이터는 단일 템플릿 복제라 값 분포가 균일한데, 인덱스 효용은 선택도에
#      달려 있다. 실제로는 COMPLETED 가 대부분이고 FAILED 는 소수일 텐데 균일하게
#      깔면 옵티마이저 카디널리티 추정이 현실과 달라진다. 그 위에서 잰 ms 는
#      '빨라졌다'의 근거가 못 된다."
#
#   맞는 거절이다. 그래서 이 rig 은 그 벽을 **우회하지 않고 독립변수로 승격**한다.
#   분포를 하나로 고정해 "N배 빨라졌다"를 내는 대신, **선택도를 훑어 곡선을 낸다.**
#   답의 형태가 바뀐다:
#
#     ❌ "인덱스로 3배 빨라졌다"        ← 균일 25% 한 점의 값. 실사용 분포에선 안 맞는다
#     ✅ "선택도 N% 아래에서만 듣고,     ← 곡선. 실사용 분포가 어디에 떨어지든 읽을 수 있다
#         그 위로는 옵티마이저가 버린다"
#
#   핵심 착상: 상태 분포를 **일부러 치우치게** 한 벌만 깔면(70/20/8/2), 같은 테이블에서
#   status 값만 바꿔 선택도 4점을 공짜로 얻는다. 재시딩이 필요 없다.
#
# ─────────────────────────────────────────────────────────────────────────────
# 팔이 셋인 이유 — 오늘의 "before" 는 2026-08-03 의 before 와 다르다
# ─────────────────────────────────────────────────────────────────────────────
#
#   §4 가 관리자 인덱스를 정당화할 때 exercise_sessions 의 인덱스는 **전부 member_id
#   선두**였다. 그런데 2026-08-07 에 집계 e 를 위해 idx_session_starttime_member
#   (start_time, member_id) 가 들어왔다(V1__baseline.sql:175). 이건 **기간으로 seek 가
#   된다** — 즉 관리자 인덱스를 빼도 오늘은 날짜 범위 조회가 예전처럼 무력하지 않다.
#
#   그래서 "인덱스 전/후"를 두 팔로 물으면 질문이 흐려진다. 셋으로 가른다:
#
#     A0  역사적 before   member_id 선두 2종만          — §4 가 정당화하던 그 상태
#     A1  오늘의 반사실   A0 + (start_time, member_id)  — 관리자 인덱스만 없는 오늘
#     B   현행           A1 + (status, start_time)     — 지금 스키마
#
#   A1↔B 가 "관리자 인덱스가 **지금도** 값을 하는가"의 답이고, A0↔A1 은 "그 값의 얼마가
#   이미 다른 인덱스로 회수됐는가"의 답이다. 둘은 다른 질문이고 섞이면 안 된다.
#
# ─────────────────────────────────────────────────────────────────────────────
# 이 rig 이 대답하지 못하는 것 (먼저 박아둔다)
# ─────────────────────────────────────────────────────────────────────────────
#
#   ⚠️ 실사용 분포가 70/20/8/2 라는 근거는 없다. 이건 **가정이고, 가정을 고정하지 않으려고
#      곡선을 낸다.** 곡선 위에서 실제 분포가 어디든 읽으면 된다. "우리 서비스는 70%다"로
#      인용하면 안 된다.
#   ⚠️ 100만 행이면 테이블이 버퍼풀에 다 들어간다(≈70MB + 인덱스). 즉 이 라운드는 **CPU
#      바운드 warm 측정**이고 디스크 I/O 는 안 잰다. 데이터가 버퍼풀을 넘기면 다른 곡선이 된다.
#   ⚠️ 쓰기 대가는 이 rig 이 안 잰다. §4-1 이 "방향조차 관측 안 됨"으로 남긴 항목은 그대로다.
#   ⚠️ 절대 ms 는 이 박스의 것이다. 팔 간 배수와 곡선의 **형태**만 인용한다.
#
# ─────────────────────────────────────────────────────────────────────────────
# 측정 위생 (앞선 라운드들이 산 교훈)
# ─────────────────────────────────────────────────────────────────────────────
#
#   · 버림 블록 1개 — 첫 블록은 통째로 버린다(#6 워밍업 교훈)
#   · 팔 순서 상쇄 — 블록마다 팔 순서를 회전한다. 팔당 1판이면 "팔"과 "판 순서"가 분리 불가
#   · 시딩 자기검증 7종 — 하나라도 틀리면 측정을 시작하지 않는다(#10 교훈)
#   · EXPLAIN ANALYZE 의 actual time — 클라이언트 wall-clock 이 아니다(§4-5 ②-1 교훈:
#     같은 쿼리가 클라이언트 0.73s ↔ 내부 425ms)
#   · 중앙값과 최소값을 같이 낸다 — 평균만 보면 분포 겹침이 안 보인다
#
set -euo pipefail

PW=${PW:-1234}
DB_NAME=${DB_NAME:-admin_sweep}
CONTAINER=${CONTAINER:-shadowfit-mysql}

USERS=${USERS:-200000}
SESSIONS=${SESSIONS:-1000000}

BLOCKS=${BLOCKS:-6}          # 측정 블록 (앞에 버림 블록 1개가 따로 붙는다)
REPEATS=${REPEATS:-5}        # 셀당 반복
OUTDIR=${OUTDIR:-/root/admin-sweep-results}

mkdir -p "$OUTDIR"
RAW="$OUTDIR/raw.tsv"
PLANS="$OUTDIR/plans.txt"
LOG="$OUTDIR/run.log"

# stderr 의 'World-writable config file' 경고는 컨테이너 이미지의 파일 권한 탓이라 무해한데,
# 매 호출마다 찍혀 로그에서 진짜 오류를 덮는다. 그 한 줄만 걸러내고 나머지 stderr 는 남긴다 —
# 통째로 /dev/null 로 보내면 진짜 실패가 조용해진다.
NOISE='World-writable config file'
M(){ docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -B "$DB_NAME" "$@" 2> >(grep -v "$NOISE" >&2); }
Mroot(){ docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -B "$@" 2> >(grep -v "$NOISE" >&2); }
die(){ echo "🔴 $*" | tee -a "$LOG" >&2; exit 1; }
say(){ echo "$*" | tee -a "$LOG"; }

say "############ 관리자 조회 선택도 스윕 ############"
say "회원 ${USERS} / 세션 ${SESSIONS} / 블록 ${BLOCKS}(+버림 1) × 반복 ${REPEATS}"
say "시작: $(date -Is)"
say ""

# ═════════════════════════════════════════════════════════════════════════════
# [1/6] 스키마
# ═════════════════════════════════════════════════════════════════════════════
say "## [1/6] 스크래치 스키마"
Mroot -e "DROP DATABASE IF EXISTS $DB_NAME; CREATE DATABASE $DB_NAME;" \
  || die "DB 생성 실패"

# 숫자 시퀀스 — 0..999,999
M -e "
DROP TABLE IF EXISTS _seq;
CREATE TABLE _seq (n INT PRIMARY KEY);
INSERT INTO _seq
SELECT d0.n+d1.n*10+d2.n*100+d3.n*1000+d4.n*10000+d5.n*100000
FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d0
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d1
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d2
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d3
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d4
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d5;" \
  || die "_seq 생성 실패"

# 실 스키마의 컬럼 구성을 따른다(V1__baseline.sql exercise_sessions).
# FK 는 뺀다 — 측정 대상이 조회 계획이고, FK 는 시딩만 느리게 한다.
M -e "
CREATE TABLE users_scale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sessions_scale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  exercise_id BIGINT NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME,
  total_reps INT DEFAULT 0,
  avg_sync_rate DECIMAL(5,2),
  status ENUM('IN_PROGRESS','COMPLETED','CANCELLED','FAILED') NOT NULL DEFAULT 'IN_PROGRESS',
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;" \
  || die "테이블 생성 실패"

# ═════════════════════════════════════════════════════════════════════════════
# [2/6] 시딩
#
# 🔴 세 값(member_id · status · start_time)이 **서로 독립**이어야 한다.
#    2026-08-06 에 이 전제가 깨져서 라운드 하나가 통째로 무효였다 — 시딩 두 줄이
#    같은 변수의 함수라 회원 99.96% 가 평생 한 상태만 가졌다(§4-2 결함 #5).
#
# 🔴 CRC32 를 쓰지 않는다. 그 판의 1차 수정이 CRC32 였는데 **그것도 틀렸다** —
#    GF(2) 위에서 선형이라 등차 입력의 구조가 하위 비트에 남는다(n vs n+200,000 에서
#    일치확률 0.0000). MD5 는 같은 자리에서 0.2524(무작위 기대 0.25).
#    → hash-function-selection.md
#
#    그래서 셋 다 **소금이 다른 MD5** 로 뽑는다.
# ═════════════════════════════════════════════════════════════════════════════
say "## [2/6] 시딩 — 회원 ${USERS}"
M -e "
INSERT INTO users_scale (id, username, email, created_at)
SELECT n+1,
       CONCAT(ELT(1+(CONV(SUBSTR(MD5(CONCAT('u',n)),1,4),16,10)%5),'kim','lee','park','choi','jung'), n),
       CONCAT('u', n, '@test.local'),
       NOW() - INTERVAL (CONV(SUBSTR(MD5(CONCAT('uc',n)),1,8),16,10) % 525600) MINUTE
FROM _seq WHERE n < $USERS;" || die "회원 시딩 실패"

say "## [2/6] 시딩 — 세션 ${SESSIONS} (치우친 상태 분포 70/20/8/2)"
# 상태는 0..999 를 잘라 매핑한다 — 70.0% / 20.0% / 8.0% / 2.0%
M -e "
INSERT INTO sessions_scale (member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status)
SELECT
  1 + (CONV(SUBSTR(MD5(CONCAT('m',n)),1,8),16,10) % $USERS),
  1 + (CONV(SUBSTR(MD5(CONCAT('e',n)),1,4),16,10) % 5),
  NOW() - INTERVAL (CONV(SUBSTR(MD5(CONCAT('t',n)),1,8),16,10) % 525600) MINUTE,
  NULL, 0, NULL,
  CASE
    WHEN (CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10) % 1000) < 700 THEN 'COMPLETED'
    WHEN (CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10) % 1000) < 900 THEN 'IN_PROGRESS'
    WHEN (CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10) % 1000) < 980 THEN 'CANCELLED'
    ELSE 'FAILED'
  END
FROM _seq WHERE n < $SESSIONS;" || die "세션 시딩 실패"

# ═════════════════════════════════════════════════════════════════════════════
# [3/6] 시딩 자기검증 7종 — 하나라도 틀리면 측정을 시작하지 않는다
#
#   "고쳤다"고 선언하기 전에 고쳐졌는지 재는 것까지가 수정이다. 2026-08-06 판은
#   스크립트가 매번 성공했는데 데이터가 틀려 있었다 — 성공은 신호가 아니다.
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [3/6] 시딩 자기검증 7종"

g(){ printf '  %-52s %s\n' "$1" "$2" | tee -a "$LOG"; }

# ① 행 수가 정확한가
N_U=$(M -e "SELECT COUNT(*) FROM users_scale;")
N_S=$(M -e "SELECT COUNT(*) FROM sessions_scale;")
[ "$N_U" = "$USERS" ]    || die "①  회원 행수 불일치: $N_U != $USERS"
[ "$N_S" = "$SESSIONS" ] || die "①  세션 행수 불일치: $N_S != $SESSIONS"
g "① 행 수" "회원 $N_U · 세션 $N_S ✅"

# ② 상태 분포가 의도한 치우침인가 (목표 대비 ±0.5%p)
DIST=$(M -e "SELECT status, ROUND(COUNT(*)*100/$SESSIONS,3) FROM sessions_scale GROUP BY status ORDER BY 2 DESC;")
echo "$DIST" | while IFS=$'\t' read -r st pct; do g "   └ $st" "${pct}%"; done
# 허용 오차를 상수로 박지 않는다 — 이건 이항 표집이므로 표준편차가 N 에서 나온다.
#   sd(%p) = 100 * sqrt(p(1-p)/N),  게이트 = 5sd
# N=100만·p=0.7 이면 0.23%p 로 조이고, 스모크(N=2만)면 1.15%p 로 느슨해진다.
# 고정 0.5%p 로 두면 규모에 따라 거짓 실패하거나 거짓 통과한다.
BAD=$(M -e "
SELECT COUNT(*) FROM (
  SELECT status,
         COUNT(*)*100/$SESSIONS AS p,
         CASE status WHEN 'COMPLETED' THEN 0.70 WHEN 'IN_PROGRESS' THEN 0.20
                     WHEN 'CANCELLED' THEN 0.08 ELSE 0.02 END AS p0
  FROM sessions_scale GROUP BY status
) t
WHERE ABS(p - p0*100) > 5 * 100 * SQRT(p0*(1-p0)/$SESSIONS);")
[ "$BAD" = "0" ] || die "②  상태 분포가 목표에서 5σ 넘게 벗어났다 (N=$SESSIONS)"
TOL=$(M -e "SELECT ROUND(5*100*SQRT(0.2*0.8/$SESSIONS),3);")
g "② 상태 분포" "목표 70/20/8/2 · 5σ 게이트 ±${TOL}%p 이내 ✅"

# ③ status ⟂ member_id  — §4-2 결함 #5 가 정확히 이 자리에서 났다
#    한 회원이 여러 상태를 갖는가. 종속이면 회원당 distinct 가 1 에 붙는다.
AVGD=$(M -e "
SELECT ROUND(AVG(d),3) FROM (
  SELECT member_id, COUNT(DISTINCT status) d
  FROM sessions_scale GROUP BY member_id HAVING COUNT(*) >= 4
) t;")
awk -v v="$AVGD" 'BEGIN{ if (v+0 < 2.0) exit 1 }' \
  || die "③  회원당 distinct status 평균이 $AVGD — status 가 member_id 에 종속돼 있다"
g "③ status ⟂ member_id" "회원당 distinct status 평균 $AVGD (≥2.0) ✅"

# ④ status ⟂ start_time — 상태별 평균 시각이 갈리면 기간 필터가 상태를 대신 고른다
#
# 🔴 여기도 "며칠" 을 상수로 박으면 안 된다. 각 상태의 평균 시각은 표본평균이고 그 표준오차는
#    se_i = sd / sqrt(n_i) 다. FAILED 는 2% 라 n 이 작아 se 가 크다 — 고정 3일 게이트는
#    N=100만에서도 5σ(≈3.7일)보다 좁아 **정상 데이터를 거짓 실패**시킨다(스모크에서 실제로 걸렸다).
#    그래서 게이트를 se 에서 만든다: |평균_i − 전체평균| ≤ 5·se_i.
VIOL=$(M -e "
SELECT COUNT(*) FROM (
  SELECT status,
         AVG(UNIX_TIMESTAMP(start_time))/86400 AS a,
         COUNT(*) AS n
  FROM sessions_scale GROUP BY status
) t
CROSS JOIN (
  SELECT AVG(UNIX_TIMESTAMP(start_time))/86400 AS ga,
         STDDEV_POP(UNIX_TIMESTAMP(start_time))/86400 AS gsd
  FROM sessions_scale
) k
WHERE ABS(t.a - k.ga) > 5 * k.gsd / SQRT(t.n);")
[ "$VIOL" = "0" ] || die "④  상태별 평균 시각이 5σ 밖으로 벌어졌다 ($VIOL 개) — start_time 이 status 에 종속"
SPREAD=$(M -e "
SELECT ROUND(MAX(a)-MIN(a),2) FROM (
  SELECT status, AVG(UNIX_TIMESTAMP(start_time))/86400 a FROM sessions_scale GROUP BY status
) t;")
g "④ status ⟂ start_time" "상태별 평균 시각 최대편차 ${SPREAD}일 · 5σ 게이트 통과 ✅"

# ⑤ member_id ⟂ start_time — 같은 방식
MVIOL=$(M -e "
SELECT COUNT(*) FROM (
  SELECT member_id % 10 AS b,
         AVG(UNIX_TIMESTAMP(start_time))/86400 AS a,
         COUNT(*) AS n
  FROM sessions_scale GROUP BY member_id % 10
) t
CROSS JOIN (
  SELECT AVG(UNIX_TIMESTAMP(start_time))/86400 AS ga,
         STDDEV_POP(UNIX_TIMESTAMP(start_time))/86400 AS gsd
  FROM sessions_scale
) k
WHERE ABS(t.a - k.ga) > 5 * k.gsd / SQRT(t.n);")
[ "$MVIOL" = "0" ] || die "⑤  회원 버킷별 평균 시각이 5σ 밖이다 ($MVIOL 개)"
MSPREAD=$(M -e "
SELECT ROUND(MAX(a)-MIN(a),2) FROM (
  SELECT member_id % 10 b, AVG(UNIX_TIMESTAMP(start_time))/86400 a
  FROM sessions_scale GROUP BY member_id % 10
) t;")
g "⑤ member_id ⟂ start_time" "회원 버킷별 최대편차 ${MSPREAD}일 · 5σ 게이트 통과 ✅"

# ⑥ 행이 2벌로 복제되지 않았는가 — §4-2 결함 #6(CROSS JOIN 오용)이 이걸 놓쳤다
DUP=$(M -e "SELECT COUNT(*) FROM (SELECT member_id, start_time FROM sessions_scale
            GROUP BY member_id, start_time HAVING COUNT(*) > 4) t;")
[ "$DUP" -lt 100 ] || die "⑥  (member_id, start_time) 가 5회 이상 겹치는 조합이 $DUP 개 — 복제 의심"
g "⑥ 복제 없음" "(member_id,start_time) 5회 이상 겹침 $DUP 건 (<100) ✅"

# ⑦ 기간이 실제로 365일에 걸쳐 있는가 — 기간폭 팔이 의미를 가지려면 필수
SPAN=$(M -e "SELECT ROUND(DATEDIFF(MAX(start_time), MIN(start_time))) FROM sessions_scale;")
[ "$SPAN" -ge 360 ] || die "⑦  세션이 ${SPAN}일에만 퍼져 있다 — 365일이어야 한다"
g "⑦ 기간 분포" "${SPAN}일 (≥360) ✅"

say "  → 7종 전부 통과. 측정을 시작한다."

# ═════════════════════════════════════════════════════════════════════════════
# [4/6] 팔 정의
# ═════════════════════════════════════════════════════════════════════════════
IDX_MEMBER="INDEX idx_session_member_status_start (member_id, status, start_time),
            INDEX idx_session_member_exercise_status_start (member_id, exercise_id, status, start_time)"

apply_arm(){
  local arm="$1"
  # 매번 전부 지우고 필요한 것만 만든다 — 팔 간 잔여 인덱스로 오염되는 것을 막는다
  for ix in idx_session_member_status_start idx_session_member_exercise_status_start \
            idx_session_starttime_member idx_session_status_starttime; do
    M -e "ALTER TABLE sessions_scale DROP INDEX $ix;" 2>/dev/null || true
  done
  M -e "ALTER TABLE sessions_scale
          ADD INDEX idx_session_member_status_start (member_id, status, start_time),
          ADD INDEX idx_session_member_exercise_status_start (member_id, exercise_id, status, start_time);" \
    || die "기본 인덱스 생성 실패"
  case "$arm" in
    A0) : ;;
    A1) M -e "ALTER TABLE sessions_scale ADD INDEX idx_session_starttime_member (start_time, member_id);" \
          || die "A1 인덱스 실패" ;;
    B)  M -e "ALTER TABLE sessions_scale
                ADD INDEX idx_session_starttime_member (start_time, member_id),
                ADD INDEX idx_session_status_starttime (status, start_time);" \
          || die "B 인덱스 실패" ;;
  esac
  M -e "ANALYZE TABLE sessions_scale;" >/dev/null
}

# 셀: 선택도 4 × 기간폭 4
# 격자를 환경변수로 뺀 이유 — 로컬(Windows/Docker Desktop)은 `docker exec` 한 번이 ~3초라
# 전 격자 스모크가 비현실적이다. 축소 격자로 배선을 먼저 검증하고, 본 라운드에서는 기본값을 쓴다.
STATUSES=${STATUSES:-"FAILED CANCELLED IN_PROGRESS COMPLETED"}   # 2% · 8% · 20% · 70%
WINDOWS=${WINDOWS:-"1 7 30 0"}                                   # 일 단위, 0 = 전체 기간

build_q(){   # $1=status $2=window  → 목록 쿼리
  local st="$1" w="$2"
  if [ "$w" = "0" ]; then
    echo "SELECT id, member_id, start_time, status FROM sessions_scale
          WHERE status='$st' ORDER BY start_time DESC LIMIT 20"
  else
    echo "SELECT id, member_id, start_time, status FROM sessions_scale
          WHERE status='$st' AND start_time >= NOW() - INTERVAL $w DAY
          ORDER BY start_time DESC LIMIT 20"
  fi
}
build_c(){   # 총건수 쿼리 — §4-3 이 "남은 진짜 비용은 여기"라고 지목한 자리
  local st="$1" w="$2"
  if [ "$w" = "0" ]; then
    echo "SELECT COUNT(*) FROM sessions_scale WHERE status='$st'"
  else
    echo "SELECT COUNT(*) FROM sessions_scale
          WHERE status='$st' AND start_time >= NOW() - INTERVAL $w DAY"
  fi
}

# EXPLAIN ANALYZE 의 루트 노드 actual time 끝값(ms)만 뽑는다.
# 클라이언트 wall-clock 을 쓰지 않는 이유: §4-5 ②-1 에서 같은 쿼리가
# 클라이언트 0.73s ↔ 내부 425ms 로 갈렸다. 연결·파싱·포맷이 섞이기 때문이다.
# 🔴 파싱에 정규식을 쓰지 않는다. 처음엔 awk -F'\\.\\.' 로 ".." 를 갈랐는데, 이 awk 에서는
#    그 이스케이프가 안 먹어 FS 가 «아무 두 글자» 정규식이 됐고 **전 셀이 조용히 빈 값**이 됐다
#    (스모크에서 raw.tsv 가 헤더만 남아 잡혔다 — 스크립트는 끝까지 성공한 것처럼 돌았다).
#    index/substr 만 쓰면 awk 구현 차이를 안 탄다.
#
#    -B(batch) 라 계획 전체가 한 줄에 \n 이스케이프로 들어온다. 그래서 첫 'actual time=' 이
#    루트 노드의 것이고, 그 '..' 뒤 값이 **끝 시각(=총 소요 ms)** 이다.
run_ms(){
  local q="$1"
  M -e "EXPLAIN ANALYZE $q;" \
    | head -1 \
    | awk '{
        i = index($0, "actual time=")
        if (i == 0) exit
        s = substr($0, i + 12)
        j = index(s, "..")
        if (j == 0) exit
        t = substr(s, j + 2)
        split(t, a, " ")
        v = a[1]
        gsub(/[^0-9.]/, "", v)
        if (v != "") print v
      }'
}

capture_plan(){
  local arm="$1" st="$2" w="$3" kind="$4" q="$5"
  {
    echo "───── arm=$arm status=$st window=${w}d kind=$kind"
    docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot "$DB_NAME" \
      -e "EXPLAIN $q\G" 2>/dev/null | grep -E 'select_type|type:|key:|rows:|filtered:|Extra:'
    echo
  } >> "$PLANS"
}

# ═════════════════════════════════════════════════════════════════════════════
# [5/6] 측정
#
#   블록마다 팔 순서를 회전한다. 팔당 1판이면 "팔 효과"와 "판 순서 효과"가 분리되지
#   않는다 — 앞선 라운드에서 "먼저 DROP 한 쪽이 느리다"는 교락이 실제로 나왔었다.
#   첫 블록(#0)은 통째로 버린다.
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [5/6] 측정 — 버림 1블록 + 본 ${BLOCKS}블록"
printf 'block\tarm\tstatus\twindow_d\tkind\trep\tms\n' > "$RAW"

ORDERS=("A0 A1 B" "B A1 A0" "A1 B A0" "A0 B A1" "B A0 A1" "A1 A0 B")

for b in $(seq 0 "$BLOCKS"); do
  ord=${ORDERS[$(( (b - 1 + ${#ORDERS[@]}) % ${#ORDERS[@]} ))]}
  if [ "$b" = "0" ]; then
    say "  [블록 0/버림] 순서: ${ORDERS[0]}"
    ord="${ORDERS[0]}"
  else
    say "  [블록 $b/$BLOCKS] 순서: $ord"
  fi

  for arm in $ord; do
    apply_arm "$arm"

    # 워밍업 — 이 팔의 모든 셀을 1회씩 돌려 버린다(버퍼풀·plan cache)
    for st in $STATUSES; do for w in $WINDOWS; do
      run_ms "$(build_q "$st" "$w")" >/dev/null
      run_ms "$(build_c "$st" "$w")" >/dev/null
    done; done

    for st in $STATUSES; do
      for w in $WINDOWS; do
        if [ "$b" = "1" ]; then
          capture_plan "$arm" "$st" "$w" list  "$(build_q "$st" "$w")"
          capture_plan "$arm" "$st" "$w" count "$(build_c "$st" "$w")"
        fi
        for r in $(seq 1 "$REPEATS"); do
          ms=$(run_ms "$(build_q "$st" "$w")")
          cs=$(run_ms "$(build_c "$st" "$w")")
          # 🔴 빈 값을 조용히 넘기지 않는다. 파서가 깨졌을 때 스크립트는 끝까지 «성공» 하고
          #    요약만 비는데, 그건 틀린 결과보다 나쁘다(실제로 한 번 그렇게 돌았다).
          if [ -z "$ms" ] || [ -z "$cs" ]; then
            die "측정값 파싱 실패 — block=$b arm=$arm status=$st window=$w (list='$ms' count='$cs')"
          fi
          if [ "$b" != "0" ]; then
            printf '%s\t%s\t%s\t%s\tlist\t%s\t%s\n'  "$b" "$arm" "$st" "$w" "$r" "$ms" >> "$RAW"
            printf '%s\t%s\t%s\t%s\tcount\t%s\t%s\n' "$b" "$arm" "$st" "$w" "$r" "$cs" >> "$RAW"
          fi
        done
      done
    done
  done
done

# ═════════════════════════════════════════════════════════════════════════════
# [6/6] 요약
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [6/6] 요약 — 중앙값 ms (괄호는 최소값)"
say ""

SUMMARY="$OUTDIR/summary.txt"
{
  echo "선택도 실측 비율:"
  M -e "SELECT status, ROUND(COUNT(*)*100/$SESSIONS,2) FROM sessions_scale GROUP BY status ORDER BY 2;" \
    | awk -F'\t' '{printf "  %-12s %s%%\n", $1, $2}'
  echo
  for kind in list count; do
    echo "════ $kind 쿼리 ════"
    for w in $WINDOWS; do
      wl=$w; [ "$w" = "0" ] && wl="전체"
      echo "  기간 ${wl}:"
      printf "    %-12s %14s %14s %14s   %s\n" "status" "A0(역사before)" "A1(오늘반사실)" "B(현행)" "A1/B"
      for st in $STATUSES; do
        line="    $(printf '%-12s' "$st")"
        declare -A med=()
        for arm in A0 A1 B; do
          v=$(awk -F'\t' -v a="$arm" -v s="$st" -v w="$w" -v k="$kind" \
              '$2==a && $3==s && $4==w && $5==k {print $7}' "$RAW" | sort -g \
              | awk '{v[NR]=$1} END{ if(NR==0){print "-"} else {print (NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2} }')
          mn=$(awk -F'\t' -v a="$arm" -v s="$st" -v w="$w" -v k="$kind" \
              '$2==a && $3==s && $4==w && $5==k {print $7}' "$RAW" | sort -g | head -1)
          med[$arm]=$v
          line="$line $(printf '%14s' "${v}(${mn:-–})")"
        done
        ratio=$(awk -v a="${med[A1]}" -v b="${med[B]}" \
                'BEGIN{ if (a=="-"||b=="-"||b+0==0) print "–"; else printf "%.2fx", a/b }')
        line="$line   $(printf '%6s' "$ratio")"
        echo "$line"
      done
      echo
    done
  done
} | tee -a "$SUMMARY" | tee -a "$LOG"

say ""
say "원자료: $RAW"
say "계획:   $PLANS"
say "요약:   $SUMMARY"
say "종료: $(date -Is)"
