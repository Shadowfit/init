#!/usr/bin/env python3
"""
치우친 분포 시더 — 옵티마이저 추정 실험용 SQL 출력.

## 왜 있나

`docs/decisions/skewed-distribution-optimizer-estimates.md`. 기존 rig 은 값 분포가 균일하다 —
주간 쿼리 rig(`measure_weekly_json_table.sh`)은 셀 안의 모든 회원이 같은 F(회원당 세션 수)를
갖는다. 이 스크립트는 **평균은 그대로 두고 회원마다 F 를 다르게** 깐다. 옵티마이저가 보는
통계(행 수 ÷ 카디널리티)는 같고 개별 회원의 실제 행 수만 달라지므로, «평균이 같은데 회원마다
계획이 갈리나» 를 잴 수 있다.

## 단계 정의 (문서 §7-1, 2026-09-23 confirm)

치우침은 Zipf 지수가 아니라 **결과 분포**로 정한다 — `--max-f` 와 `--mean-f` 를 주면 지수 s 는
이 스크립트가 이분 탐색으로 찾는다.

    균일  --max-f 7   --mean-f 7     (s = 0, 기존 격자의 F=7 셀과 같다)
    약함  --max-f 50  --mean-f 7
    강함  --max-f 365 --mean-f 7

회원 i(활동량 순위, 1 = 가장 활발)의 세션 수 F_i = max(1, round(max_f · i^-s)). 합이 총 세션 수가
되도록 s 를 맞추고, 반올림 잔차는 꼬리 회원에 1씩 나눠 준다.

상태 비율은 `--status-mix` 로 받는다. 문서 §7-2 의 (c) 는 09-10 관리자 선택도 스윕과 같은
`COMPLETED=70,IN_PROGRESS=20,CANCELLED=8,FAILED=2` 다 — **이 값도 가정이다**(그 rig 주석이 스스로
적었다). 기본값을 두지 않는다. 근거 없는 값을 스크립트가 대신 고르지 않게 하려는 것이다.

⚠️ IN_PROGRESS 20% 는 앱 불변식(회원당 IN_PROGRESS 1개, `createSession`)을 어긴다. 09-10 rig 도 같았다.
   옵티마이저 추정 실험에는 상관없지만, 이 데이터로 앱 로직을 돌리면 안 된다.

## 대상 주 배치 — 기존 주간 rig 와 같은 규칙

회원마다 앞의 min(F, W) 건은 대상 주(`--week-from` 부터 7일)에 고르게, 나머지는 그 앞 52주에
무작위로 흩는다. 그래서 균일 셀(F=7, W=7)은 기존 격자의 F=7·W=7 셀과 같은 모양이다.

⚠️ 평균 F 와 W 가 같으면(7·7) 세션 대부분이 대상 주 한 주에 몰린다(균일 100%, 강함 77%). 주간
   쿼리(T1)에는 그게 기존 격자와 같은 조건이지만, 기간 필터가 핵심인 관리자 쿼리(T3·T4)에는
   왜곡이다. T3·T4 용 데이터는 `--week-sessions 0` 으로 만든다 — 전부 52주에 흩는다.

## id 섞기

활동량 순위를 member_id 에 그대로 매기면 헤비 유저가 작은 id 에 몰려 물리적 배치(클러스터드
인덱스의 페이지)까지 달라진다. 순위 → member_id 대응을 `--rng-seed` 로 섞는다. 측정에 쓸
헤비·중간·라이트 회원의 id 는 자기검증 출력에 적는다.

## 출력

stdout 으로 SQL 한 벌: DB 생성 → 표 4개(users·exercises·exercise_sessions·session_reports) → 적재
→ `ANALYZE TABLE` 은 하지 않는다(통계 팔 S0 = «적재 직후 그대로» 를 남기기 위해서다. 팔은 측정
하니스가 건다). 자기검증 요약은 stderr 로.

스키마·인덱스는 `measure_weekly_json_table.sh` 의 표와 같다(V1__baseline.sql 기준, FK 제외).
리포트는 COMPLETED 세션에만 만든다 — 앱에서 리포트는 완료 트랜잭션에서만 생기므로
(`SessionCompletionTx`).

## 사용 예

    python gen_skew_seed.py --db shadowfit_skew_strong --total-sessions 100000 \\
        --max-f 365 --mean-f 7 --week-sessions 7 --reps 30 \\
        --status-mix COMPLETED=70,IN_PROGRESS=20,CANCELLED=8,FAILED=2 --rng-seed 1 \\
        > skew_strong.sql
    docker exec -i shadowfit-mysql mysql -uroot -p1234 < skew_strong.sql
"""
import argparse
import random
import sys
from datetime import datetime, timedelta

STATUSES = ("IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED")  # V1 ENUM 순서
BATCH = 1000


def parse_mix(text):
    mix = {}
    for part in text.split(","):
        key, _, val = part.partition("=")
        key = key.strip().upper()
        if key not in STATUSES:
            sys.exit(f"!! 모르는 상태: {key} (허용: {', '.join(STATUSES)})")
        mix[key] = float(val)
    total = sum(mix.values())
    if total <= 0:
        sys.exit("!! --status-mix 합이 0 이하")
    return {k: v / total for k, v in mix.items()}


def fanouts(members, max_f, total):
    """합이 total 이고 최댓값이 max_f 인 Zipf 꼴 회원별 세션 수(순위 순, 내림차순)."""
    if max_f * members < total:
        sys.exit(f"!! max_f({max_f}) × 회원({members}) < 총 세션({total}) — 최댓값이 평균보다 작다")
    if members > total:
        sys.exit("!! 회원이 세션보다 많다 — 모든 회원은 최소 1건을 갖는다")

    def build(s):
        return [max(1, round(max_f * (i ** -s))) for i in range(1, members + 1)]

    if max_f * members == total:
        return [max_f] * members, 0.0

    lo, hi = 0.0, 50.0
    for _ in range(100):
        mid = (lo + hi) / 2
        if sum(build(mid)) > total:
            lo = mid
        else:
            hi = mid
    s = hi  # hi 쪽은 합이 total 이하다 → 잔차 diff ≥ 0
    f = build(s)
    diff = total - sum(f)
    # 반올림 잔차는 꼬리부터 +1. 최대 회원(순위 0)은 건드리지 않는다 — max_f 가 단계 정의다.
    # 순위가 뒤집히지 않게 앞 회원보다 작은 자리만 고르고, 한 바퀴 돌아도 자리가 없으면 그 조건을 푼다.
    strict = True
    while diff > 0:
        changed = False
        for i in range(members - 1, 0, -1):
            if diff == 0:
                break
            if f[i] < max_f and (not strict or f[i] < f[i - 1]):
                f[i] += 1
                diff -= 1
                changed = True
        if not changed:
            if not strict:
                sys.exit("!! 잔차를 나눠 줄 자리가 없다")
            strict = False
    return f, s


def pick_status(rng, cum):
    x = rng.random()
    for key, edge in cum:
        if x < edge:
            return key
    return cum[-1][0]


def main():
    ap = argparse.ArgumentParser(description="치우친 분포 시더 — 옵티마이저 추정 실험용 SQL 출력")
    ap.add_argument("--db", required=True)
    ap.add_argument("--total-sessions", type=int, required=True)
    ap.add_argument("--max-f", type=int, required=True, help="가장 활발한 회원의 세션 수")
    ap.add_argument("--mean-f", type=int, required=True, help="회원당 평균 세션 수 (회원 수 = total / mean)")
    ap.add_argument("--week-sessions", type=int, required=True, help="회원당 대상 주에 넣을 최대 세션 수 W")
    ap.add_argument("--reps", type=int, required=True, help="리포트 repTrend 원소 수 R")
    ap.add_argument("--status-mix", required=True, help="예: COMPLETED=70,IN_PROGRESS=20,CANCELLED=8,FAILED=2")
    ap.add_argument("--rng-seed", type=int, required=True)
    ap.add_argument("--week-from", default="2025-10-01 00:00:00")
    args = ap.parse_args()

    rng = random.Random(args.rng_seed)
    members = args.total_sessions // args.mean_f
    total = members * args.mean_f
    f, s = fanouts(members, args.max_f, total)

    mix = parse_mix(args.status_mix)
    cum, acc = [], 0.0
    for key in STATUSES:
        if key in mix:
            acc += mix[key]
            cum.append((key, acc))

    member_ids = list(range(1, members + 1))
    rng.shuffle(member_ids)  # 순위 r(0-based) → member_ids[r]

    week_from = datetime.strptime(args.week_from, "%Y-%m-%d %H:%M:%S")
    history_from = week_from - timedelta(weeks=52)
    history_minutes = 52 * 7 * 24 * 60
    W = args.week_sessions

    out = sys.stdout.write
    out(f"-- gen_skew_seed.py {' '.join(sys.argv[1:])}\n")
    out("SET NAMES utf8mb4;\n")
    out(f"DROP DATABASE IF EXISTS {args.db};\nCREATE DATABASE {args.db} CHARACTER SET utf8mb4;\nUSE {args.db};\n")
    out("""
CREATE TABLE users (
  id BIGINT PRIMARY KEY,
  email VARCHAR(100) NOT NULL UNIQUE,
  username VARCHAR(50) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE exercises (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO exercises VALUES (1, '스쿼트');
CREATE TABLE exercise_sessions (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE session_reports (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
""")

    rows = []

    def flush_users():
        out("INSERT INTO users (id, email, username) VALUES " + ",".join(rows) + ";\n")
        rows.clear()

    for mid in range(1, members + 1):
        rows.append(f"({mid},'m{mid}@skew.test','m{mid}')")
        if len(rows) >= BATCH:
            flush_users()
    if rows:
        flush_users()

    def flush_sessions():
        out("INSERT INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, total_reps, "
            "avg_sync_rate, status, created_at) VALUES " + ",".join(rows) + ";\n")
        rows.clear()

    status_count = {k: 0 for k in STATUSES}
    week_total = 0
    sid = 0
    # 세션 id 는 시간순이 아니라 회원 순이다 — 기존 rig 의 id = n+1 과 같은 성질(삽입 순서 ≠ 시각).
    for rank, fi in enumerate(f):
        mid = member_ids[rank]
        in_week = min(fi, W)
        for k in range(fi):
            sid += 1
            if k < in_week:
                st = week_from + timedelta(hours=6, minutes=k * 10080 // in_week)
                week_total += 1
            else:
                st = history_from + timedelta(minutes=rng.randrange(history_minutes))
            status = pick_status(rng, cum)
            status_count[status] += 1
            done = status != "IN_PROGRESS"
            et = f"'{st + timedelta(minutes=15):%Y-%m-%d %H:%M:%S}'" if done else "NULL"
            reps = str(args.reps) if status == "COMPLETED" else "NULL"
            sync = "75.00" if status == "COMPLETED" else "NULL"
            rows.append(f"({sid},{mid},1,'{st:%Y-%m-%d %H:%M:%S}',{et},{reps},{sync},'{status}',"
                        f"'{st:%Y-%m-%d %H:%M:%S}')")
            if len(rows) >= BATCH:
                flush_sessions()
    if rows:
        flush_sessions()

    # 리포트 — COMPLETED 에만. JSON 모양·값은 기존 rig 과 같다(syncRate 75~94 반복, worst 는 해시).
    trend = ",".join(
        f'{{"repNumber":{n + 1},"syncRate":{75 + n % 20}.0,"timeStamp":"01:15"}}' for n in range(args.reps)
    )
    out(f"""
INSERT INTO session_reports (id, member_id, session_id, detailed_analysis, created_at)
SELECT s.id, s.member_id, s.id,
       CONCAT('{{"worstSection":{{"repNumber":', 1 + (CONV(SUBSTRING(MD5(CONCAT('wr', s.id)), 1, 8), 16, 10) % {args.reps}),
              ',"exerciseName":"스쿼트","timeStamp":"01:15","reason":"n회차 · 싱크로율 75%"}},',
              '"repTrend":[{trend}]}}'),
       s.end_time
FROM exercise_sessions s WHERE s.status = 'COMPLETED';
""")

    # ── 자기검증 (stderr) ─────────────────────────────────────────────────────
    srt = sorted(f, reverse=True)
    top1 = sum(srt[: max(1, members // 100)]) / total
    top10 = sum(srt[: max(1, members // 10)]) / total
    heavy = member_ids[0]
    median = member_ids[members // 2]
    light = member_ids[-1]
    log = sys.stderr.write
    log(f"== gen_skew_seed 자기검증 ({args.db})\n")
    log(f"   회원 {members} · 세션 {total} · 평균 F {total / members:.2f} · 최대 F {srt[0]} · 최소 F {srt[-1]} · 지수 s={s:.4f}\n")
    log(f"   상위 1% 회원의 세션 비중 {top1:.1%} · 상위 10% {top10:.1%}\n")
    log("   상태 " + " · ".join(f"{k} {status_count[k] / total:.2%}" for k in STATUSES) + "\n")
    log(f"   대상 주({week_from:%Y-%m-%d}~+7일) 전체 세션 {week_total}\n")
    log(f"   측정용 회원 — 헤비 id={heavy} (F={f[0]}) · 중간 id={median} (F={f[members // 2]}) · 라이트 id={light} (F={f[-1]})\n")
    if srt[0] != args.max_f:
        log(f"   !! 최대 F 가 {srt[0]} — 목표 {args.max_f} 와 다르다. 이 셀은 무효\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
