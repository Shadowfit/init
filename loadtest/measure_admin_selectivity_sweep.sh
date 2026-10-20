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

# 무대 — 관리자 화면 둘이 성질이 다르다. 같은 격자·같은 위생으로 각각 잰다.
#
#   sessions (B 화면) : 필터가 status 등치라 (status, start_time) 로 «구획 seek» 이 가능하다
#   members  (A 화면) : 인덱스가 created_at 하나뿐이고 나머지 필터(페르소나·레벨·온보딩·검색어)는
#                       전부 인덱스 밖이다. 그래서 목록 조회는 구조적으로 «최신순으로 훑으며
#                       거르다가 20건 채우면 멈추기» 가 되고, 비용이 **필터 선택도에 반비례**한다.
#                       B 화면에서 관찰된 그 메커니즘이 여기서는 «기본 계획» 이라는 뜻이다.
TARGET=${TARGET:-sessions}
case "$TARGET" in
  sessions|members) : ;;
  *) echo "🔴 모르는 TARGET: '$TARGET' — sessions · members 중 하나여야 한다" >&2; exit 1 ;;
esac

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

# 🔴 버퍼풀 크기를 명시적으로 고정한다 — 이건 편의가 아니라 **비교 조건**이다.
#    100만 행이면 데이터 ~70MB + 인덱스 ~140MB 라 기본값(128MB)에서는 테이블이 통째로
#    안 들어간다. 그러면 팔마다 "무엇이 캐시에 남아 있었나"가 달라져 측정이 인덱스 효과가
#    아니라 캐시 운에 흔들린다. 넉넉히 잡아 **전 팔이 warm 조건에서 같은 무대**를 쓰게 한다.
#    (그래서 이 라운드가 재는 것은 CPU 바운드 warm 비용이고, 디스크 I/O 는 안 잰다 — 헤더 참고)
BP_SIZE=${BP_SIZE:-2147483648}
Mroot -e "SET GLOBAL innodb_buffer_pool_size = $BP_SIZE;" 2>/dev/null || true
BP_NOW=$(Mroot -e "SELECT @@innodb_buffer_pool_size;")
say "  버퍼풀: $((BP_NOW/1024/1024)) MB (요청 $((BP_SIZE/1024/1024)) MB)"
[ "$BP_NOW" -ge 1073741824 ] \
  || die "버퍼풀이 $((BP_NOW/1024/1024))MB 다 — 1GB 미만이면 팔 간 캐시 조건이 안 맞는다"

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
  selected_persona ENUM('BEGINNER','ADVANCED','DIET','REHAB') NOT NULL DEFAULT 'BEGINNER',
  workout_level VARCHAR(20),
  onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_username (username),
  UNIQUE KEY uk_users_email (email)
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
say "## [2/6] 시딩 — 회원 ${USERS} (페르소나도 치우친 분포 70/20/8/2)"
# 페르소나·가입일·이름은 소금이 다른 MD5 로 뽑아 서로 독립이다(세션 쪽과 같은 이유).
M -e "
INSERT INTO users_scale (id, username, email, selected_persona, workout_level, onboarding_completed, created_at)
SELECT n+1,
       CONCAT(ELT(1+(CONV(SUBSTR(MD5(CONCAT('u',n)),1,4),16,10)%5),'kim','lee','park','choi','jung'), n),
       CONCAT('u', n, '@test.local'),
       CASE
         WHEN (CONV(SUBSTR(MD5(CONCAT('p',n)),1,8),16,10) % 1000) < 700 THEN 'BEGINNER'
         WHEN (CONV(SUBSTR(MD5(CONCAT('p',n)),1,8),16,10) % 1000) < 900 THEN 'ADVANCED'
         WHEN (CONV(SUBSTR(MD5(CONCAT('p',n)),1,8),16,10) % 1000) < 980 THEN 'DIET'
         ELSE 'REHAB'
       END,
       ELT(1+(CONV(SUBSTR(MD5(CONCAT('w',n)),1,4),16,10)%3),'BEGINNER','INTERMEDIATE','ADVANCED'),
       (CONV(SUBSTR(MD5(CONCAT('o',n)),1,4),16,10) % 2) = 0,
       NOW() - INTERVAL (CONV(SUBSTR(MD5(CONCAT('uc',n)),1,8),16,10) % 525600) MINUTE
FROM _seq WHERE n < $USERS;" || die "회원 시딩 실패"

if [ "$TARGET" = "members" ]; then
  say "  TARGET=members — 세션 시딩은 건너뛴다(측정 대상이 users 다)"
fi

if [ "$TARGET" = "sessions" ]; then
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
fi

# ═════════════════════════════════════════════════════════════════════════════
# [3/6] 시딩 자기검증 7종 — 하나라도 틀리면 측정을 시작하지 않는다
#
#   "고쳤다"고 선언하기 전에 고쳐졌는지 재는 것까지가 수정이다. 2026-08-06 판은
#   스크립트가 매번 성공했는데 데이터가 틀려 있었다 — 성공은 신호가 아니다.
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [3/6] 시딩 자기검증 7종"

g(){ printf '  %-52s %s\n' "$1" "$2" | tee -a "$LOG"; }

if [ "$TARGET" = "sessions" ]; then
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

else
# ══ TARGET=members 의 게이트 ══
#   세션판과 같은 성질을 users 에서 확인한다. 차이는 «묶는 열» 뿐이다 —
#   세션은 member_id 로 묶었고 여기는 그런 열이 없어 id 버킷을 쓴다.

# ① 행 수
N_U=$(M -e "SELECT COUNT(*) FROM users_scale;")
[ "$N_U" = "$USERS" ] || die "①  회원 행수 불일치: $N_U != $USERS"
g "① 행 수" "회원 $N_U ✅"

# ② 페르소나 분포 — 5σ 게이트(이항 표집오차에서 유도)
M -e "SELECT selected_persona, ROUND(COUNT(*)*100/$USERS,3) FROM users_scale
      GROUP BY selected_persona ORDER BY 2 DESC;" \
  | while IFS=$'\t' read -r p pct; do g "   └ $p" "${pct}%"; done
BAD=$(M -e "
SELECT COUNT(*) FROM (
  SELECT selected_persona,
         COUNT(*)*100/$USERS AS p,
         CASE selected_persona WHEN 'BEGINNER' THEN 0.70 WHEN 'ADVANCED' THEN 0.20
                               WHEN 'DIET' THEN 0.08 ELSE 0.02 END AS p0
  FROM users_scale GROUP BY selected_persona
) t
WHERE ABS(p - p0*100) > 5 * 100 * SQRT(p0*(1-p0)/$USERS);")
[ "$BAD" = "0" ] || die "②  페르소나 분포가 5σ 밖이다 (N=$USERS)"
TOL=$(M -e "SELECT ROUND(5*100*SQRT(0.2*0.8/$USERS),3);")
g "② 페르소나 분포" "목표 70/20/8/2 · 5σ 게이트 ±${TOL}%p 이내 ✅"

# ③ 페르소나 ⟂ id — 시딩 순서가 페르소나를 결정하면 안 된다
PVIOL=$(M -e "
SELECT COUNT(*) FROM (
  SELECT id % 10 AS b, SUM(selected_persona='BEGINNER')*1.0/COUNT(*) AS r, COUNT(*) AS n
  FROM users_scale GROUP BY id % 10
) t
WHERE ABS(t.r - 0.70) > 5 * SQRT(0.70*0.30/t.n);")
[ "$PVIOL" = "0" ] || die "③  id 버킷별 페르소나 비율이 5σ 밖이다 ($PVIOL 개) — 페르소나가 id 에 종속"
g "③ 페르소나 ⟂ id" "id 버킷 10개 전부 5σ 이내 ✅"

# ④ 페르소나 ⟂ created_at — 갈리면 기간 필터가 페르소나를 대신 고른다
VIOL=$(M -e "
SELECT COUNT(*) FROM (
  SELECT selected_persona, AVG(UNIX_TIMESTAMP(created_at))/86400 AS a, COUNT(*) AS n
  FROM users_scale GROUP BY selected_persona
) t
CROSS JOIN (
  SELECT AVG(UNIX_TIMESTAMP(created_at))/86400 AS ga,
         STDDEV_POP(UNIX_TIMESTAMP(created_at))/86400 AS gsd FROM users_scale
) k
WHERE ABS(t.a - k.ga) > 5 * k.gsd / SQRT(t.n);")
[ "$VIOL" = "0" ] || die "④  페르소나별 평균 가입일이 5σ 밖이다 ($VIOL 개)"
SPREAD=$(M -e "
SELECT ROUND(MAX(a)-MIN(a),2) FROM (
  SELECT selected_persona, AVG(UNIX_TIMESTAMP(created_at))/86400 a FROM users_scale
  GROUP BY selected_persona) t;")
g "④ 페르소나 ⟂ created_at" "페르소나별 평균 가입일 최대편차 ${SPREAD}일 · 5σ 통과 ✅"

# ⑤ id ⟂ created_at — 시딩 순서가 가입일 순서면 «최신 20건» 이 항상 같은 구간이 된다
MVIOL=$(M -e "
SELECT COUNT(*) FROM (
  SELECT id % 10 AS b, AVG(UNIX_TIMESTAMP(created_at))/86400 AS a, COUNT(*) AS n
  FROM users_scale GROUP BY id % 10
) t
CROSS JOIN (
  SELECT AVG(UNIX_TIMESTAMP(created_at))/86400 AS ga,
         STDDEV_POP(UNIX_TIMESTAMP(created_at))/86400 AS gsd FROM users_scale
) k
WHERE ABS(t.a - k.ga) > 5 * k.gsd / SQRT(t.n);")
[ "$MVIOL" = "0" ] || die "⑤  id 버킷별 평균 가입일이 5σ 밖이다 ($MVIOL 개)"
g "⑤ id ⟂ created_at" "id 버킷별 평균 가입일 5σ 이내 ✅"

# ⑥ 가입일이 한 점에 뭉치지 않았는가 — distinct 가 적으면 «최신순» 이 사실상 무작위가 된다
DC=$(M -e "SELECT COUNT(DISTINCT created_at) FROM users_scale;")
awk -v d="$DC" -v n="$USERS" 'BEGIN{ if (d+0 < n*0.5) exit 1 }' \
  || die "⑥  distinct created_at 이 $DC 뿐이다 (행 $USERS) — 동률이 많아 정렬이 불안정하다"
g "⑥ 가입일 동률" "distinct created_at $DC / $USERS ✅"

# ⑦ 기간
SPAN=$(M -e "SELECT ROUND(DATEDIFF(MAX(created_at), MIN(created_at))) FROM users_scale;")
[ "$SPAN" -ge 360 ] || die "⑦  회원이 ${SPAN}일에만 퍼져 있다 — 365일이어야 한다"
g "⑦ 기간 분포" "${SPAN}일 (≥360) ✅"
fi

say "  → 7종 전부 통과. 측정을 시작한다."

# ═════════════════════════════════════════════════════════════════════════════
# [4/6] 팔 정의
# ═════════════════════════════════════════════════════════════════════════════
apply_arm_members(){
  local arm="$1"
  # 실 스키마의 users 는 보조 인덱스가 idx_users_created_at 하나뿐이다(V1__baseline.sql:24).
  # username·email UNIQUE 는 실제로도 있으므로 팔과 무관하게 항상 둔다.
  for ix in idx_users_created_at idx_users_persona_created; do
    M -e "ALTER TABLE users_scale DROP INDEX $ix;" 2>/dev/null || true
  done
  case "$arm" in
    A0) : ;;                                                     # 보조 인덱스 없음 — §4 의 "users 는 아예 없다"
    B)  M -e "ALTER TABLE users_scale ADD INDEX idx_users_created_at (created_at);" \
          || die "B 인덱스 실패" ;;
    C)  M -e "ALTER TABLE users_scale
                ADD INDEX idx_users_created_at (created_at),
                ADD INDEX idx_users_persona_created (selected_persona, created_at);" \
          || die "C 인덱스 실패" ;;
  esac
  M -e "ANALYZE TABLE users_scale;" >/dev/null
}

apply_arm_sessions(){
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

apply_arm(){ if [ "$TARGET" = "members" ]; then apply_arm_members "$1"; else apply_arm_sessions "$1"; fi; }

# 셀: 선택도 4 × 기간폭 4
# 격자를 환경변수로 뺀 이유 — 로컬(Windows/Docker Desktop)은 `docker exec` 한 번이 ~3초라
# 전 격자 스모크가 비현실적이다. 축소 격자로 배선을 먼저 검증하고, 본 라운드에서는 기본값을 쓴다.
# STATUSES 는 «필터 값 축» 이다 — 세션이면 status, 회원이면 selected_persona. 둘 다 70/20/8/2 로 깔린다.
if [ "$TARGET" = "members" ]; then
  STATUSES=${STATUSES:-"REHAB DIET ADVANCED BEGINNER"}             # 2% · 8% · 20% · 70%
  AXIS_LABEL="persona"
  ARMS_ALL="A0 B C"
  ARM_HEAD1="A0(인덱스없음)"; ARM_HEAD2="B(현행 created_at)"; ARM_HEAD3="C(+페르소나복합)"
  RATIO_NUM=B; RATIO_DEN=C; RATIO_HEAD="B/C"
else
  STATUSES=${STATUSES:-"FAILED CANCELLED IN_PROGRESS COMPLETED"}   # 2% · 8% · 20% · 70%
  AXIS_LABEL="status"
  ARMS_ALL="A0 A1 B"
  ARM_HEAD1="A0(역사before)"; ARM_HEAD2="A1(오늘반사실)"; ARM_HEAD3="B(현행)"
  RATIO_NUM=A1; RATIO_DEN=B; RATIO_HEAD="A1/B"
fi
WINDOWS=${WINDOWS:-"1 7 30 0"}                                     # 일 단위, 0 = 전체 기간

build_q(){   # $1=필터값 $2=기간  → 목록 쿼리
  local st="$1" w="$2"
  if [ "$TARGET" = "members" ]; then
    if [ "$w" = "0" ]; then
      echo "SELECT id, username, email, selected_persona, created_at FROM users_scale
            WHERE selected_persona='$st' ORDER BY created_at DESC LIMIT 20"
    else
      echo "SELECT id, username, email, selected_persona, created_at FROM users_scale
            WHERE selected_persona='$st' AND created_at >= NOW() - INTERVAL $w DAY
            ORDER BY created_at DESC LIMIT 20"
    fi
  else
    if [ "$w" = "0" ]; then
      echo "SELECT id, member_id, start_time, status FROM sessions_scale
            WHERE status='$st' ORDER BY start_time DESC LIMIT 20"
    else
      echo "SELECT id, member_id, start_time, status FROM sessions_scale
            WHERE status='$st' AND start_time >= NOW() - INTERVAL $w DAY
            ORDER BY start_time DESC LIMIT 20"
    fi
  fi
}
build_c(){   # 총건수 쿼리 — §4-3 이 "남은 진짜 비용은 여기"라고 지목한 자리
  local st="$1" w="$2"
  if [ "$TARGET" = "members" ]; then
    if [ "$w" = "0" ]; then
      echo "SELECT COUNT(*) FROM users_scale WHERE selected_persona='$st'"
    else
      echo "SELECT COUNT(*) FROM users_scale
            WHERE selected_persona='$st' AND created_at >= NOW() - INTERVAL $w DAY"
    fi
  else
    if [ "$w" = "0" ]; then
      echo "SELECT COUNT(*) FROM sessions_scale WHERE status='$st'"
    else
      echo "SELECT COUNT(*) FROM sessions_scale
            WHERE status='$st' AND start_time >= NOW() - INTERVAL $w DAY"
    fi
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

if [ "$TARGET" = "members" ]; then
  ORDERS=("A0 B C" "C B A0" "B C A0" "A0 C B" "C A0 B" "B A0 C")
else
  ORDERS=("A0 A1 B" "B A1 A0" "A1 B A0" "A0 B A1" "B A0 A1" "A1 A0 B")
fi

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
  if [ "$TARGET" = "members" ]; then
    M -e "SELECT selected_persona, ROUND(COUNT(*)*100/$USERS,2) FROM users_scale
          GROUP BY selected_persona ORDER BY 2;" \
      | awk -F'\t' '{printf "  %-12s %s%%\n", $1, $2}'
  else
    M -e "SELECT status, ROUND(COUNT(*)*100/$SESSIONS,2) FROM sessions_scale GROUP BY status ORDER BY 2;" \
      | awk -F'\t' '{printf "  %-12s %s%%\n", $1, $2}'
  fi
  echo
  for kind in list count; do
    echo "════ $kind 쿼리 ════"
    for w in $WINDOWS; do
      wl=$w; [ "$w" = "0" ] && wl="전체"
      echo "  기간 ${wl}:"
      printf "    %-12s %18s %18s %18s   %s\n" "$AXIS_LABEL" "$ARM_HEAD1" "$ARM_HEAD2" "$ARM_HEAD3" "$RATIO_HEAD"
      for st in $STATUSES; do
        line="    $(printf '%-12s' "$st")"
        declare -A med=()
        for arm in $ARMS_ALL; do
          v=$(awk -F'\t' -v a="$arm" -v s="$st" -v w="$w" -v k="$kind" \
              '$2==a && $3==s && $4==w && $5==k {print $7}' "$RAW" | sort -g \
              | awk '{v[NR]=$1} END{ if(NR==0){print "-"} else {print (NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2} }')
          mn=$(awk -F'\t' -v a="$arm" -v s="$st" -v w="$w" -v k="$kind" \
              '$2==a && $3==s && $4==w && $5==k {print $7}' "$RAW" | sort -g | head -1)
          med[$arm]=$v
          line="$line $(printf '%18s' "${v}(${mn:-–})")"
        done
        ratio=$(awk -v a="${med[$RATIO_NUM]}" -v b="${med[$RATIO_DEN]}" \
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
