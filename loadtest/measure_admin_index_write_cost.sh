#!/usr/bin/env bash
# 관리자 인덱스의 **쓰기 대가** — 조용한 박스에서 처음으로 신호를 얻는다
# (admin-page-scope.md §4-1 「쓰기 — 방향조차 관측되지 않는다」, 2026-09-10)
#
# ─────────────────────────────────────────────────────────────────────────────
# 왜 다시 재는가 — 지난 판은 «못 쟀다» 로 끝났다
# ─────────────────────────────────────────────────────────────────────────────
#
#   §4-1 은 쓰기 결론을 **철회**했다. 배수가 아니라 **방향조차** 안 나왔기 때문이고,
#   원인을 스스로 이렇게 적었다:
#
#     "로컬 2코어 동거 환경이라 분포가 겹친다. (…) 분리 환경에서 재는 것은
#      정밀도를 높이는 일이 아니라 **처음으로 신호를 얻는 일**이다."
#
#   그래서 이 rig 은 새로운 기법을 쓰지 않는다 — **무대만 바꾼다.** 조용한 EC2(MySQL 단독)에서
#   지난 판이 이미 옳게 잡아뒀던 설계(쌍둥이 테이블 + 팔 교대)를 그대로 돌린다.
#
# ─────────────────────────────────────────────────────────────────────────────
# 왜 읽기 rig(measure_admin_selectivity_sweep.sh)에 얹지 않고 파일을 나눴나
# ─────────────────────────────────────────────────────────────────────────────
#
#   회원/세션 무대는 «같은 실험의 다른 무대» 라 TARGET 스위치로 한 파일에 뒀다.
#   쓰기는 **다른 실험**이다 — 격자가 없고(선택도·기간이 무의미), 측정 원시값이 다르다
#   (EXPLAIN ANALYZE 가 INSERT 를 지원하지 않아 서버측 SYSDATE(6) 차분을 쓴다).
#   억지로 합치면 두 실험의 전제가 한 파일에서 섞인다.
#
# ─────────────────────────────────────────────────────────────────────────────
# 이 rig 이 대답하지 못하는 것
# ─────────────────────────────────────────────────────────────────────────────
#
#   ⚠️ 배치 INSERT 의 대가다. 행 단위 INSERT 를 N번 하는 경로의 대가가 아니다
#      (다만 이 프로젝트의 세션 적재는 실제로 배치다 — problem-solving-log #1)
#   ⚠️ UPDATE·DELETE 는 안 잰다. 상태 전이 UPDATE 는 §4-5-1 이 «인덱스 컬럼을 안 건드려
#      엔트리가 이동하지 않는다» 고 구조로 논증해뒀고, 이 라운드는 그걸 확인하지 않는다
#   ⚠️ 절대 시간은 이 박스의 것이다. **팔 간 배수만** 쓴다
#
set -euo pipefail

PW=${PW:-1234}
DB_NAME=${DB_NAME:-admin_write}
CONTAINER=${CONTAINER:-shadowfit-mysql}

USERS=${USERS:-200000}         # 각 users 쌍둥이의 기준 행수
SESSIONS=${SESSIONS:-1000000}  # 각 sessions 쌍둥이의 기준 행수
PER_ROUND=${PER_ROUND:-10000}  # 라운드당 INSERT 행 수
WARMUP=${WARMUP:-3}            # 버릴 블록
BLOCKS=${BLOCKS:-12}           # 측정 블록
OUTDIR=${OUTDIR:-/root/admin-write-results}

mkdir -p "$OUTDIR"
RAW="$OUTDIR/raw.tsv"
LOG="$OUTDIR/run.log"

NOISE='World-writable config file'
M(){ docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -B "$DB_NAME" "$@" 2> >(grep -v "$NOISE" >&2); }
Mroot(){ docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -B "$@" 2> >(grep -v "$NOISE" >&2); }
die(){ echo "🔴 $*" | tee -a "$LOG" >&2; exit 1; }
say(){ echo "$*" | tee -a "$LOG"; }

say "############ 관리자 인덱스 쓰기 대가 ############"
say "users ${USERS} × 3 쌍둥이 / sessions ${SESSIONS} × 2 쌍둥이"
say "라운드당 ${PER_ROUND}행 · 버림 ${WARMUP}블록 + 본 ${BLOCKS}블록"
say "시작: $(date -Is)"

# ═════════════════════════════════════════════════════════════════════════════
# [1/5] 스키마 + 버퍼풀
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [1/5] 스키마"
Mroot -e "DROP DATABASE IF EXISTS $DB_NAME; CREATE DATABASE $DB_NAME;" || die "DB 생성 실패"

BP_SIZE=${BP_SIZE:-2147483648}
Mroot -e "SET GLOBAL innodb_buffer_pool_size = $BP_SIZE;" 2>/dev/null || true
BP_NOW=$(Mroot -e "SELECT @@innodb_buffer_pool_size;")
say "  버퍼풀: $((BP_NOW/1024/1024)) MB"
[ "$BP_NOW" -ge 1073741824 ] || die "버퍼풀이 1GB 미만이다 — 팔 간 조건이 안 맞는다"

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
  || die "_seq 실패"

# ── 팔 정의 ──────────────────────────────────────────────────────────────
#
#   users 는 실 스키마의 보조 인덱스가 created_at 하나뿐이다(V1__baseline.sql:24).
#   username·email UNIQUE 는 실제로도 있으므로 전 팔에 둔다 — 이게 «인덱스 없음» 팔의
#   기준선이 0 이 아닌 이유다.
#
#   sessions 는 현 구성이 4개다(2026-08-07 통합 후). 관리자 인덱스 유무만 가른다 —
#   §4-1 이 못 잰 바로 그 한 개다.
#
#   🔴 쌍둥이는 «같은 내용, 다른 인덱스» 여야 한다. 같은 MD5 식으로 채우므로 행이 글자
#      그대로 같고, 아래 [3/5] 가 그것을 확인한 뒤에만 측정을 시작한다.
mk_users(){   # $1=테이블명 $2=추가인덱스SQL(비어도 됨)
  M -e "
  CREATE TABLE $1 (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    selected_persona ENUM('BEGINNER','ADVANCED','DIET','REHAB') NOT NULL DEFAULT 'BEGINNER',
    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_${1}_username (username),
    UNIQUE KEY uk_${1}_email (email)
    ${2:+, $2}
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;" || die "$1 생성 실패"
}
mk_sessions(){
  M -e "
  CREATE TABLE $1 (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    exercise_id BIGINT NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    total_reps INT DEFAULT 0,
    avg_sync_rate DECIMAL(5,2),
    status ENUM('IN_PROGRESS','COMPLETED','CANCELLED','FAILED') NOT NULL DEFAULT 'IN_PROGRESS',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_${1}_a (member_id, status, start_time),
    INDEX idx_${1}_b (member_id, exercise_id, status, start_time),
    INDEX idx_${1}_c (start_time, member_id)
    ${2:+, $2}
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;" || die "$1 생성 실패"
}

mk_users users_u0 ""
mk_users users_u1 "INDEX idx_users_u1_created (created_at)"
mk_users users_u2 "INDEX idx_users_u2_created (created_at), INDEX idx_users_u2_pc (selected_persona, created_at)"
mk_sessions sessions_s0 ""
mk_sessions sessions_s1 "INDEX idx_sessions_s1_ss (status, start_time)"

# ═════════════════════════════════════════════════════════════════════════════
# [2/5] 기준 시딩 — 쌍둥이는 글자 그대로 같은 내용이어야 한다
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [2/5] 기준 시딩"
for t in users_u0 users_u1 users_u2; do
  say "  $t ← ${USERS}"
  M -e "
  INSERT INTO $t (id, username, email, selected_persona, onboarding_completed, created_at)
  SELECT n+1,
         CONCAT('user', n), CONCAT('u', n, '@test.local'),
         CASE
           WHEN (CONV(SUBSTR(MD5(CONCAT('p',n)),1,8),16,10) % 1000) < 700 THEN 'BEGINNER'
           WHEN (CONV(SUBSTR(MD5(CONCAT('p',n)),1,8),16,10) % 1000) < 900 THEN 'ADVANCED'
           WHEN (CONV(SUBSTR(MD5(CONCAT('p',n)),1,8),16,10) % 1000) < 980 THEN 'DIET'
           ELSE 'REHAB' END,
         (CONV(SUBSTR(MD5(CONCAT('o',n)),1,4),16,10) % 2) = 0,
         NOW() - INTERVAL (CONV(SUBSTR(MD5(CONCAT('uc',n)),1,8),16,10) % 525600) MINUTE
  FROM _seq WHERE n < $USERS;" || die "$t 시딩 실패"
done
for t in sessions_s0 sessions_s1; do
  say "  $t ← ${SESSIONS}"
  M -e "
  INSERT INTO $t (member_id, exercise_id, start_time, status)
  SELECT 1 + (CONV(SUBSTR(MD5(CONCAT('m',n)),1,8),16,10) % $USERS),
         1 + (CONV(SUBSTR(MD5(CONCAT('e',n)),1,4),16,10) % 5),
         NOW() - INTERVAL (CONV(SUBSTR(MD5(CONCAT('t',n)),1,8),16,10) % 525600) MINUTE,
         CASE
           WHEN (CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10) % 1000) < 700 THEN 'COMPLETED'
           WHEN (CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10) % 1000) < 900 THEN 'IN_PROGRESS'
           WHEN (CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10) % 1000) < 980 THEN 'CANCELLED'
           ELSE 'FAILED' END
  FROM _seq WHERE n < $SESSIONS;" || die "$t 시딩 실패"
done

# ═════════════════════════════════════════════════════════════════════════════
# [3/5] 쌍둥이 동일성 검증 — 다르면 측정이 «인덱스 차이» 가 아니다
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [3/5] 쌍둥이 동일성"
g(){ printf '  %-46s %s\n' "$1" "$2" | tee -a "$LOG"; }

for pair in "users_u0 users_u1" "users_u0 users_u2" "sessions_s0 sessions_s1"; do
  set -- $pair
  a=$1; b=$2
  na=$(M -e "SELECT COUNT(*) FROM $a;"); nb=$(M -e "SELECT COUNT(*) FROM $b;")
  [ "$na" = "$nb" ] || die "$a($na) ≠ $b($nb) — 쌍둥이 행수가 다르다"
  # 내용까지 같은지: 체크섬으로 본다(행수만 같고 값이 다르면 B+tree 모양이 달라진다)
  if [ "${a#users}" != "$a" ]; then
    ca=$(M -e "SELECT BIT_XOR(CRC32(CONCAT_WS(',',id,username,selected_persona,created_at))) FROM $a;")
    cb=$(M -e "SELECT BIT_XOR(CRC32(CONCAT_WS(',',id,username,selected_persona,created_at))) FROM $b;")
  else
    ca=$(M -e "SELECT BIT_XOR(CRC32(CONCAT_WS(',',id,member_id,status,start_time))) FROM $a;")
    cb=$(M -e "SELECT BIT_XOR(CRC32(CONCAT_WS(',',id,member_id,status,start_time))) FROM $b;")
  fi
  [ "$ca" = "$cb" ] || die "$a ↔ $b 내용 체크섬 불일치 ($ca ≠ $cb)"
  g "$a ↔ $b" "행수 $na · 체크섬 일치 ✅"
done

# ═════════════════════════════════════════════════════════════════════════════
# [4/5] 측정
#
#   INSERT 는 EXPLAIN ANALYZE 가 지원하지 않으므로 **서버측 SYSDATE(6) 차분**으로 잰다.
#   NOW() 가 아니라 SYSDATE() 인 이유 — NOW() 는 문장 시작 시각으로 고정돼 차분이 0 이 된다.
#   한 번의 docker exec 안에서 끝나므로 클라이언트 왕복이 값에 안 들어간다.
# ═════════════════════════════════════════════════════════════════════════════
say ""
say "## [4/5] 측정"
printf 'block\ttable\tarm\tms\n' > "$RAW"

ins_users(){   # $1=테이블 $2=오프셋
  M -e "
  SET @t0 = SYSDATE(6);
  INSERT INTO $1 (username, email, selected_persona, onboarding_completed, created_at)
  SELECT CONCAT('w$2_', n), CONCAT('w$2_', n, '@test.local'),
         CASE
           WHEN (CONV(SUBSTR(MD5(CONCAT('wp$2',n)),1,8),16,10) % 1000) < 700 THEN 'BEGINNER'
           WHEN (CONV(SUBSTR(MD5(CONCAT('wp$2',n)),1,8),16,10) % 1000) < 900 THEN 'ADVANCED'
           WHEN (CONV(SUBSTR(MD5(CONCAT('wp$2',n)),1,8),16,10) % 1000) < 980 THEN 'DIET'
           ELSE 'REHAB' END,
         FALSE, NOW()
  FROM _seq WHERE n < $PER_ROUND;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t0, SYSDATE(6))/1000;" | tail -1
}
ins_sessions(){
  M -e "
  SET @t0 = SYSDATE(6);
  INSERT INTO $1 (member_id, exercise_id, start_time, status)
  SELECT 1 + (CONV(SUBSTR(MD5(CONCAT('wm$2',n)),1,8),16,10) % $USERS),
         1 + (CONV(SUBSTR(MD5(CONCAT('we$2',n)),1,4),16,10) % 5),
         NOW(), 'IN_PROGRESS'
  FROM _seq WHERE n < $PER_ROUND;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t0, SYSDATE(6))/1000;" | tail -1
}

# 팔 순서를 블록마다 회전한다 — 팔 효과와 판 순서 효과를 분리하기 위해서다.
U_ORDERS=("u0 u1 u2" "u2 u1 u0" "u1 u2 u0" "u0 u2 u1" "u2 u0 u1" "u1 u0 u2")
S_ORDERS=("s0 s1" "s1 s0")

TOTAL=$((WARMUP + BLOCKS))
for b in $(seq 1 "$TOTAL"); do
  uo=${U_ORDERS[$(( (b-1) % ${#U_ORDERS[@]} ))]}
  so=${S_ORDERS[$(( (b-1) % ${#S_ORDERS[@]} ))]}
  tag="본"; [ "$b" -le "$WARMUP" ] && tag="버림"
  say "  [블록 $b/$TOTAL · $tag] users:$uo  sessions:$so"

  for arm in $uo; do
    ms=$(ins_users "users_$arm" "${b}_${arm}")
    [ -n "$ms" ] || die "users_$arm 측정값이 비었다 (블록 $b)"
    [ "$b" -gt "$WARMUP" ] && printf '%s\tusers\t%s\t%s\n' "$b" "$arm" "$ms" >> "$RAW"
  done
  for arm in $so; do
    ms=$(ins_sessions "sessions_$arm" "${b}_${arm}")
    [ -n "$ms" ] || die "sessions_$arm 측정값이 비었다 (블록 $b)"
    [ "$b" -gt "$WARMUP" ] && printf '%s\tsessions\t%s\t%s\n' "$b" "$arm" "$ms" >> "$RAW"
  done
done

# ═════════════════════════════════════════════════════════════════════════════
# [5/5] 요약
# ═════════════════════════════════════════════════════════════════════════════
say ""
SUMMARY="$OUTDIR/summary.txt"
{
  echo "라운드당 ${PER_ROUND}행 · 본 ${BLOCKS}블록 (버림 ${WARMUP})"
  echo
  stat(){ awk -F'\t' -v t="$1" -v a="$2" '$2==t && $3==a {print $4}' "$RAW" | sort -g \
          | awk '{v[NR]=$1} END{ if(NR==0){print "- - -"} else {
              med=(NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2
              printf "%s %s %s", med, v[1], v[NR] } }'; }
  for tbl in users sessions; do
    echo "════ $tbl ════"
    printf "  %-28s %10s %10s %10s\n" "팔" "중앙값" "최소" "최대"
    if [ "$tbl" = "users" ]; then arms="u0 u1 u2"; else arms="s0 s1"; fi
    for a in $arms; do
      case "$a" in
        u0) L="u0 보조인덱스 없음" ;;
        u1) L="u1 +created_at (현행)" ;;
        u2) L="u2 +created_at+(persona,ca)" ;;
        s0) L="s0 관리자인덱스 없음(3개)" ;;
        s1) L="s1 +(status,start_time) (현행)" ;;
      esac
      read -r med mn mx <<<"$(stat "$tbl" "$a")"
      printf "  %-28s %10s %10s %10s\n" "$L" "$med" "$mn" "$mx"
    done
    echo
    echo "  배수 (중앙값 기준):"
    if [ "$tbl" = "users" ]; then
      b0=$(stat users u0 | awk '{print $1}')
      b1=$(stat users u1 | awk '{print $1}')
      b2=$(stat users u2 | awk '{print $1}')
      awk -v a="$b0" -v b="$b1" -v c="$b2" 'BEGIN{
        printf "    현행 인덱스의 대가        u1/u0 = %.4f\n", b/a
        printf "    페르소나 복합 추가 대가   u2/u1 = %.4f\n", c/b
        printf "    둘 합쳐                   u2/u0 = %.4f\n", c/a }'
    else
      c0=$(stat sessions s0 | awk '{print $1}')
      c1=$(stat sessions s1 | awk '{print $1}')
      awk -v a="$c0" -v b="$c1" 'BEGIN{
        printf "    관리자 인덱스의 대가      s1/s0 = %.4f\n", b/a }'
    fi
    echo
  done
  echo "블록별 원자료는 raw.tsv — 추세(테이블이 커지며 느려지는지)를 확인할 것"
} | tee -a "$SUMMARY" | tee -a "$LOG"

say ""
say "원자료: $RAW"
say "종료: $(date -Is)"
