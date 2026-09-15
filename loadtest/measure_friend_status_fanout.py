"""친구 현황 streak 쿼리 팬아웃(N+1) — GET /groups/{id}/members/status 의 비용이 멤버 수 N 에 어떻게 비례하나.

설계: docs/decisions/friend-status-streak-fanout-experiment-design.md (§1~§6).

무엇을 재나
    요청 하나 = 고정 쿼리 4 + 멤버당 streak 커서 쿼리 1 (후보 a, 현재 구현)
                 vs 고정 쿼리 4 + LATERAL 한 방 (후보 b, ATTENDANCE_STREAK_STRATEGY=batch)
    N ∈ {1, 5, 12, 30, 100} 스윕으로 기울기(ms/멤버)를 재고, a−b 델타의 교차점 N 을 찾는다.

판 구조 (§4, 후보 전환 방식만 설계 문서와 다르다 — 아래 «🔴 조정»)
    rep(3회) × 후보 블록(a, b — rep 마다 순서 교대) × N 5수준(라틴 방격 행)
    셀 한 판 = 같은 요청 --requests 회 순차(c=1). 매 블록 시작에 백엔드를 그 후보로 재기동하고
    --warmup 회를 버린다. 라운드 첫 판은 버림판(분석 제외).

🔴 조정 ([[feedback_decision_doc]] 정신 — 조용히 바꾸지 않고 여기 명시): 설계 §4 는 (후보, N) 10셀을
    한 라틴 방격으로 섞으라고 했는데, 후보 전환이 프로세스 재기동(설정값)이라 셀마다 재기동하면
    JVM 워밍업이 셀마다 끼어 N 효과를 오염시킨다. 그래서 후보를 «블록» 으로 묶고(블록당 재기동 1회 +
    워밍업 버림), 블록 순서를 rep 마다 교대(a→b, b→a, a→b)해 «후보 = 시간대» 가 되지 않게 했다.
    N 순서는 블록 안에서 라틴 방격 행을 따른다. 요청 헤더로 후보를 고르는 방식은 운영 코드에 실험용
    분기를 하나 더 넣는 것이라 택하지 않았다.

지표 (§5) — 판 전후 델타 ÷ 요청 수
    응답 지연 p50·p95·mean (클라이언트, ms)
    Com_select / 요청                      ← «N+4» 가 실제인가
    Handler_read_{key,prev,next,first} / 요청 ← a: ≈ N×(streak+1), b: ≈ N×31 예측
    performance_schema digest SUM_TIMER_WAIT / 요청 ← 지연 중 DB 실행 몫
    hikaricp_connections_usage_seconds (sum/count 델타) ← 요청당 커넥션 점유

시드
    그룹 5개(N 별 1개). 그룹당 N 명 ACTIVE, 전원 streak 3(오늘·어제·그제 COMPLETED 1건씩).
    요청자(그룹 OWNER, N 에 포함)는 API 로 가입·로그인, 나머지 N−1 명은 SQL 로 직접 삽입
    (로그인 안 하므로 비밀번호 해시는 요청자 것을 복사). 이메일 접두 fsf- 로 멱등 — 있으면 재사용.

실행
    python measure_friend_status_fanout.py [--n-levels 1,5,12,30,100] [--reps 3] [--requests 200]
        [--warmup 100] [--candidates per-member,batch] [--out results/friend-status-fanout-local-YYYY-MM-DD]
        [--skip-seed] [--no-restart]

    --no-restart 는 «지금 떠 있는 백엔드의 전략을 바꾸지 않고» 한 후보만 잰다(--candidates 하나로).
    EC2: BACKEND_HOST / PROM_URL / MYSQL_CMD(예: "mysql -h db -uroot -pXXX shadowfit") / RESTART_CMD 로 덮는다.
"""

import argparse
import json
import os
import random
import re
import shlex
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

HOST = os.environ.get("BACKEND_HOST", "localhost")
PORT = int(os.environ.get("BACKEND_PORT", "8080"))
BASE_URL = f"http://{HOST}:{PORT}"
HEALTH_URL = os.environ.get("HEALTH_URL", f"http://{HOST}:9090/actuator/health")
PROMETHEUS_URL = os.environ.get("PROM_URL", f"http://{HOST}:9090/actuator/prometheus")
MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "shadowfit-mysql")
MYSQL_CMD = os.environ.get("MYSQL_CMD")  # 주면 docker exec 대신 이 셸 명령으로 SQL 을 보낸다
RESTART_CMD = os.environ.get("RESTART_CMD")  # 주면 후보 전환 재기동을 이 명령으로 (환경변수 ATTENDANCE_STREAK_STRATEGY 를 읽어야 함)
REPO_ROOT = Path(__file__).resolve().parent.parent

SEED_PREFIX = "fsf"
PASSWORD = "Passw0rd!1"
EXERCISE_ID = int(os.environ.get("EXERCISE_ID", "1"))
STREAK_DAYS = 3  # §1 통제 변수 — 전원 동일 3일 (recommendation-algorithm.md §10 재현 조건)

STATUS_VARS = ["Com_select", "Handler_read_key", "Handler_read_prev", "Handler_read_next", "Handler_read_first",
               "Handler_read_rnd_next", "Questions"]


# ── 헬퍼 ────────────────────────────────────────────────────────────────────

def _env():
    env = {}
    p = REPO_ROOT / ".env"
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, _, v = line.partition("=")
                env[k.strip()] = v.strip()
    return env


def mysql_query(sql):
    if MYSQL_CMD:
        cmd = shlex.split(MYSQL_CMD) + ["-N", "-e", sql]
    else:
        env = _env()
        cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
               "-uroot", f"-p{env['MYSQL_ROOT_PASSWORD']}", env["MYSQL_DATABASE"], "-N", "-e", sql]
    return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL)


def _maybe_json(raw):
    try:
        return json.loads(raw.decode("utf-8")) if raw else None
    except ValueError:
        return raw.decode("utf-8", "replace")


def http_json(method, path, body=None, token=None, timeout=30):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE_URL + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, _maybe_json(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, _maybe_json(e.read())


def login(email):
    status, body = http_json("POST", "/member/login", {"email": email, "password": PASSWORD})
    assert status == 200, f"login({email}) 실패: {status} {body}"
    return body["accessToken"]


def signup_and_login(username, email):
    status, body = http_json("POST", "/member/signup",
                             {"username": username, "email": email, "password": PASSWORD, "sex": "MALE"})
    # 멱등 — 이미 있으면(«이미 가입된 사용자», 400) 그대로 로그인한다.
    if status != 200 and not (status == 400 and "이미 가입" in str(body)):
        raise AssertionError(f"signup({email}) 실패: {status} {body}")
    return login(email)


def wait_healthy(timeout_s=180):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        try:
            with urllib.request.urlopen(HEALTH_URL, timeout=3) as r:
                if b'"status":"UP"' in r.read():
                    return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("백엔드가 제한 시간 안에 UP 이 안 됐다")


def mysql_status():
    # 이 스냅샷 자체의 SELECT 2개도 Com_select 에 섞인다 — 요청 200회당 +0.01 이라 무시한다.
    names = ",".join(f"'{v}'" for v in STATUS_VARS)
    # performance_schema.global_status 에는 Com_* 가 없다(SHOW 로만 나온다) — 그래서 SHOW.
    out = mysql_query(f"SHOW GLOBAL STATUS WHERE Variable_name IN ({names})")
    d = {}
    for line in out.strip().splitlines():
        k, v = line.split("\t")
        d[k] = int(v)
    # DB 실행 시간 합 (ps digest). TRUNCATE 는 안 한다 — 델타로 본다.
    out = mysql_query("SELECT COALESCE(SUM(SUM_TIMER_WAIT),0), COALESCE(SUM(COUNT_STAR),0) "
                      "FROM performance_schema.events_statements_summary_by_digest "
                      "WHERE SCHEMA_NAME = DATABASE()")
    w, c = out.strip().split("\t")
    d["digest_timer_ps"] = int(w)  # picoseconds
    d["digest_count"] = int(c)
    return d


def prom_metrics():
    """HikariCP 커넥션 점유 — usage_seconds sum/count. 없으면 빈 dict (obs 없이도 actuator prometheus 는 있다)."""
    try:
        with urllib.request.urlopen(PROMETHEUS_URL, timeout=5) as r:
            text = r.read().decode("utf-8")
    except Exception:
        return {}
    d = {}
    for key in ("hikaricp_connections_usage_seconds_sum", "hikaricp_connections_usage_seconds_count",
                "hikaricp_connections_acquire_seconds_sum", "hikaricp_connections_acquire_seconds_count",
                "hikaricp_connections_pending"):
        m = re.search(rf"^{key}\{{[^}}]*\}}\s+([0-9.eE+-]+)$", text, re.M)
        if m:
            d[key] = float(m.group(1))
    return d


# ── 시드 ────────────────────────────────────────────────────────────────────

def seed_group(n):
    """N 명짜리 그룹 하나. 반환: (group_id, requester_token). 멱등 — 같은 이메일이 있으면 재사용."""
    owner_email = f"{SEED_PREFIX}-{n}-owner@test.com"
    owner_name = f"{SEED_PREFIX}-{n}-owner"
    token = signup_and_login(owner_name, owner_email)
    owner_id = int(mysql_query(f"SELECT id FROM users WHERE email='{owner_email}'").strip())

    existing = mysql_query(
        f"SELECT g.id FROM workout_groups g JOIN group_members gm ON gm.group_id = g.id "
        f"WHERE gm.member_id = {owner_id} AND gm.role = 'OWNER' AND g.name = '{SEED_PREFIX}-N{n}'").strip()
    if existing:
        group_id = int(existing)
    else:
        status, body = http_json("POST", "/groups", {"name": f"{SEED_PREFIX}-N{n}"}, token=token)
        assert status == 201, f"그룹 생성 실패: {status} {body}"
        group_id = body["id"]

    # 나머지 N−1 명 — 로그인 안 하므로 SQL 로. 비밀번호 해시는 owner 것을 복사.
    for i in range(1, n):
        email = f"{SEED_PREFIX}-{n}-m{i}@test.com"
        mysql_query(
            "INSERT IGNORE INTO users (email, password, username, sex, role, selected_persona, onboarding_completed) "
            f"SELECT '{email}', password, '{SEED_PREFIX}-{n}-m{i}', 'MALE', 'USER', 'BEGINNER', 1 "
            f"FROM users WHERE id = {owner_id}")
    mysql_query(
        "INSERT IGNORE INTO group_members (group_id, member_id, role, status) "
        f"SELECT {group_id}, id, 'MEMBER', 'ACTIVE' FROM users "
        f"WHERE email LIKE '{SEED_PREFIX}-{n}-m%@test.com'")

    # 전원 streak 3 — 오늘·어제·그제 COMPLETED 1건씩 (이미 있으면 그대로).
    today = date.today()
    for d in range(STREAK_DAYS):
        day = today - timedelta(days=d)
        st = datetime.combine(day, datetime.min.time()).replace(hour=10)
        mysql_query(
            "INSERT INTO exercise_sessions (member_id, exercise_id, start_time, end_time, status, total_reps, avg_sync_rate) "
            f"SELECT u.id, {EXERCISE_ID}, '{st:%Y-%m-%d %H:%M:%S}', '{st + timedelta(minutes=20):%Y-%m-%d %H:%M:%S}', "
            f"'COMPLETED', 20, 80.00 FROM users u "
            f"WHERE (u.email = '{owner_email}' OR u.email LIKE '{SEED_PREFIX}-{n}-m%@test.com') "
            f"AND NOT EXISTS (SELECT 1 FROM exercise_sessions s WHERE s.member_id = u.id "
            f"AND s.status = 'COMPLETED' AND DATE(s.start_time) = '{day:%Y-%m-%d}')")

    # 검증 — N 명, 전원 streak 3
    status, body = http_json("GET", f"/groups/{group_id}/members/status", token=token)
    assert status == 200, f"members/status 실패: {status} {body}"
    assert len(body) == n, f"N={n} 인데 {len(body)} 명"
    bad = [m for m in body if m["streak"] != STREAK_DAYS or not m["attendedToday"]]
    assert not bad, f"시드 검증 실패(N={n}): {bad[:3]}"
    return group_id, token


def ensure_exercise():
    n = mysql_query(f"SELECT COUNT(*) FROM exercises WHERE id = {EXERCISE_ID}").strip()
    if n != "1":
        sys.exit(f"exercises.id={EXERCISE_ID} 가 없다 — 깨끗한 볼륨이면 exercises 는 시드되지 않는다"
                 "(tasks/32-deferred-items.md P2). EXERCISE_ID 를 맞추거나 행을 먼저 넣을 것.")


# ── 실행 계획 기록 ─────────────────────────────────────────────────────────

def capture_explain(out_dir, groups):
    """두 후보의 streak 쿼리 실행 계획을 한 번 남긴다 — «역방향 인덱스 걷기인가» 를 카운터가 아니라 계획으로 본다."""
    n = max(groups)
    gid, _ = groups[n]
    ids = mysql_query(f"SELECT member_id FROM group_members WHERE group_id = {gid} AND status = 'ACTIVE' ORDER BY member_id").split()
    if not ids:
        return
    before = f"{date.today() + timedelta(days=1):%Y-%m-%d} 00:00:00"
    single = (f"SELECT start_time FROM exercise_sessions WHERE member_id = {ids[0]} AND status = 'COMPLETED' "
              f"AND start_time < '{before}' ORDER BY start_time DESC LIMIT {31}")
    lateral = (f"SELECT m.member_id, s.start_time FROM (SELECT id AS member_id FROM users WHERE id IN ({','.join(ids)})) m "
               f"JOIN LATERAL (SELECT start_time FROM exercise_sessions WHERE member_id = m.member_id AND status = 'COMPLETED' "
               f"AND start_time < '{before}' ORDER BY start_time DESC LIMIT 31) s ORDER BY m.member_id, s.start_time DESC")
    parts = []
    for label, sql in (("per-member (회원 1명)", single), (f"batch LATERAL (N={n})", lateral)):
        parts.append("## " + label + '\n\n' + sql + '\n\n')
        for fmt in ("TREE", "TRADITIONAL"):
            try:
                parts.append("### EXPLAIN FORMAT=" + fmt + '\n' + mysql_query(f"EXPLAIN FORMAT={fmt} {sql}") + '\n')
            except subprocess.CalledProcessError as e:
                parts.append(f"### EXPLAIN FORMAT={fmt} 실패: {e}" + '\n')
        try:
            parts.append("### EXPLAIN ANALYZE" + '\n' + mysql_query(f"EXPLAIN ANALYZE {sql}") + '\n')
        except subprocess.CalledProcessError as e:
            parts.append(f"### EXPLAIN ANALYZE 실패: {e}" + '\n')
    (out_dir / "explain.txt").write_text("".join(parts), encoding="utf-8")


# ── 재기동 ──────────────────────────────────────────────────────────────────

def restart_backend(strategy):
    env = dict(os.environ, ATTENDANCE_STREAK_STRATEGY=strategy)
    if RESTART_CMD:
        subprocess.check_call(RESTART_CMD, shell=True, env=env, cwd=REPO_ROOT)
    else:
        # 프로젝트 이름을 고정한다 — 워크트리에서 돌리면 폴더 이름이 프로젝트가 돼 컨테이너 이름이 충돌한다.
        project = os.environ.get("COMPOSE_PROJECT_NAME", "init")
        subprocess.check_call(["docker", "compose", "-p", project, "up", "-d", "--force-recreate", "shadowfit-backend"],
                              env=env, cwd=REPO_ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    wait_healthy()


def current_strategy():
    """떠 있는 백엔드가 어느 후보로 도는지 — 컨테이너 env 로 확인(로컬 docker 만)."""
    try:
        out = subprocess.check_output(["docker", "inspect", "shadowfit-backend",
                                       "--format", "{{range .Config.Env}}{{println .}}{{end}}"], text=True)
        for line in out.splitlines():
            if line.startswith("ATTENDANCE_STREAK_STRATEGY="):
                return line.split("=", 1)[1]
    except Exception:
        pass
    return None


# ── 한 판 ───────────────────────────────────────────────────────────────────

def run_cell(group_id, token, n, requests, discard):
    path = f"/groups/{group_id}/members/status"
    s0 = mysql_status()
    p0 = prom_metrics()
    lat = []
    t_wall0 = time.perf_counter()
    for _ in range(requests):
        t0 = time.perf_counter()
        status, body = http_json("GET", path, token=token)
        lat.append((time.perf_counter() - t0) * 1000.0)
        if status != 200 or len(body) != n:
            raise AssertionError(f"N={n} 응답 이상: {status} len={len(body) if isinstance(body, list) else body}")
    wall = time.perf_counter() - t_wall0
    s1 = mysql_status()
    p1 = prom_metrics()

    lat_sorted = sorted(lat)
    def pct(p):
        return lat_sorted[min(len(lat_sorted) - 1, int(round(p / 100.0 * (len(lat_sorted) - 1))))]
    cell = {
        "n": n, "requests": requests, "discard": discard, "wall_s": round(wall, 3),
        "p50_ms": round(pct(50), 3), "p95_ms": round(pct(95), 3), "p99_ms": round(pct(99), 3),
        "mean_ms": round(statistics.fmean(lat), 3), "stdev_ms": round(statistics.pstdev(lat), 3),
    }
    for k in STATUS_VARS:
        cell[f"{k}_per_req"] = round((s1[k] - s0[k]) / requests, 3)
    cell["db_exec_ms_per_req"] = round((s1["digest_timer_ps"] - s0["digest_timer_ps"]) / 1e9 / requests, 4)
    cell["db_stmts_per_req"] = round((s1["digest_count"] - s0["digest_count"]) / requests, 3)
    if "hikaricp_connections_usage_seconds_sum" in p0 and "hikaricp_connections_usage_seconds_sum" in p1:
        du = p1["hikaricp_connections_usage_seconds_sum"] - p0["hikaricp_connections_usage_seconds_sum"]
        dc = p1["hikaricp_connections_usage_seconds_count"] - p0["hikaricp_connections_usage_seconds_count"]
        cell["hikari_usage_ms_per_req"] = round(du * 1000.0 / requests, 4)
        cell["hikari_checkouts_per_req"] = round(dc / requests, 3)
    if "hikaricp_connections_acquire_seconds_sum" in p0 and "hikaricp_connections_acquire_seconds_sum" in p1:
        da = p1["hikaricp_connections_acquire_seconds_sum"] - p0["hikaricp_connections_acquire_seconds_sum"]
        cell["hikari_acquire_ms_per_req"] = round(da * 1000.0 / requests, 4)
    return cell


def latin_rows(levels, reps, rng):
    """수준 k 개의 순환 라틴 방격에서 rep 수만큼 행을 뽑는다(행 순서는 섞는다) — 같은 N 이 늘 같은 자리에 오지 않게."""
    k = len(levels)
    rows = [[levels[(r + c) % k] for c in range(k)] for r in range(k)]
    rng.shuffle(rows)
    return [rows[i % k] for i in range(reps)]


# ── 요약 ────────────────────────────────────────────────────────────────────

def slope(points):
    """(n, y) 최소제곱 기울기·절편·R²."""
    if len(points) < 2:
        return None
    xs = [p[0] for p in points]; ys = [p[1] for p in points]
    mx = statistics.fmean(xs); my = statistics.fmean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    a = my - b * mx
    ss_res = sum((y - (a + b * x)) ** 2 for x, y in zip(xs, ys))
    ss_tot = sum((y - my) ** 2 for y in ys) or 1e-12
    return {"slope_ms_per_member": round(b, 4), "intercept_ms": round(a, 3), "r2": round(1 - ss_res / ss_tot, 4)}


def summarize(cells, candidate_order):
    kept = [c for c in cells if not c["discard"]]
    out = {"by_candidate": {}}
    for cand in [c for c in candidate_order if any(x["candidate"] == c for x in kept)]:
        rows = {}
        for n in sorted({c["n"] for c in kept if c["candidate"] == cand}):
            cs = [c for c in kept if c["candidate"] == cand and c["n"] == n]
            def agg(key):
                vals = [c[key] for c in cs if key in c]
                if not vals:
                    return None
                return {"mean": round(statistics.fmean(vals), 4),
                        "sd": round(statistics.pstdev(vals), 4) if len(vals) > 1 else 0.0, "k": len(vals)}
            rows[n] = {k: agg(k) for k in ("p50_ms", "p95_ms", "mean_ms", "Com_select_per_req",
                                            "Handler_read_key_per_req", "Handler_read_prev_per_req",
                                            "Handler_read_next_per_req", "db_exec_ms_per_req",
                                            "hikari_usage_ms_per_req", "hikari_checkouts_per_req",
                                            "hikari_acquire_ms_per_req")}
        pts = [(n, r["p50_ms"]["mean"]) for n, r in rows.items() if r["p50_ms"]]
        out["by_candidate"][cand] = {"rows": rows, "p50_fit": slope(pts)}
    # a−b 델타 (같은 N)
    cands = list(out["by_candidate"])
    if len(cands) == 2:
        a, b = cands
        delta = {}
        for n in out["by_candidate"][a]["rows"]:
            ra = out["by_candidate"][a]["rows"][n]["p50_ms"]; rb = out["by_candidate"][b]["rows"].get(n, {}).get("p50_ms")
            if ra and rb:
                d = ra["mean"] - rb["mean"]
                noise = max(ra["sd"], rb["sd"])
                delta[n] = {"a_minus_b_ms": round(d, 4), "max_sd_ms": round(noise, 4),
                            "beyond_noise": abs(d) > noise}
        out["delta_p50"] = {"a": a, "b": b, "by_n": delta}
    return out


def write_readme(out_dir, args, summary, meta):
    lines = [f"# 친구 현황 streak 팬아웃 실측 — {meta['where']} {meta['date']}", "",
             "설계: `docs/decisions/friend-status-streak-fanout-experiment-design.md`. "
             f"요청 {args.requests}회/셀 · 워밍업 {args.warmup} · rep {args.reps} · c=1 · streak 전원 {STREAK_DAYS}.", "",
             f"백엔드 커밋: `{meta['git']}` · 시작 {meta['started']} · 끝 {meta['finished']}", "",
             "> ⚠️ 절대값은 이 박스의 것이다 — 로컬(같은 호스트 docker)이면 왕복(RTT) 항이 거의 0 이라 "
             "기울기의 크기로 «안 아프다» 고 닫지 말 것(설계 §6). 메커니즘(SQL 수·읽은 행·기울기의 존재)만 믿는다.", "",
             "> MySQL 카운터(SQL/req·Handler_read_*)와 Hikari 지표는 **서버 전역 델타 ÷ 요청 수**다 — 아웃박스 발행기 tick(5s) 같은 "
             "백그라운드 쿼리가 섞인다. checkouts/req 가 1 근처면 요청분이 지배적이라는 뜻이고, 크게 벗어나면 그 판은 의심할 것. "
             "쿼리별 실행 계획은 `explain.txt`.", ""]
    for cand, blk in summary["by_candidate"].items():
        lines += [f"## 후보 `{cand}`", "",
                  "| N | p50 ms (sd) | p95 ms | SQL/req | Handler_read_key/req | Handler_read_prev/req | Handler_read_next/req | DB exec ms/req | Hikari usage ms/req | Hikari checkouts/req |",
                  "|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|"]
        for n, r in blk["rows"].items():
            def f(k, digits=3):
                v = r.get(k)
                return "—" if not v else f"{v['mean']:.{digits}f}" + (f" ({v['sd']:.{digits}f})" if k == "p50_ms" else "")
            lines.append(f"| {n} | {f('p50_ms')} | {f('p95_ms')} | {f('Com_select_per_req', 2)} | "
                         f"{f('Handler_read_key_per_req', 1)} | {f('Handler_read_prev_per_req', 1)} | "
                         f"{f('Handler_read_next_per_req', 1)} | {f('db_exec_ms_per_req', 4)} | {f('hikari_usage_ms_per_req', 4)} | {f('hikari_checkouts_per_req', 2)} |")
        fit = blk["p50_fit"]
        if fit:
            lines += ["", f"p50 ~ N 최소제곱: 기울기 **{fit['slope_ms_per_member']} ms/멤버**, 절편 {fit['intercept_ms']} ms, R² {fit['r2']}"]
        lines.append("")
    if "delta_p50" in summary:
        d = summary["delta_p50"]
        lines += [f"## p50 델타 `{d['a']}` − `{d['b']}`", "", "| N | a−b ms | max sd | 잡음 밖 |", "|--:|--:|--:|:--:|"]
        for n, r in d["by_n"].items():
            lines.append(f"| {n} | {r['a_minus_b_ms']:+.3f} | {r['max_sd_ms']:.3f} | {'✅' if r['beyond_noise'] else '—'} |")
        lines += ["", "«잡음 밖» = |a−b| 가 두 후보 반복 표준편차의 큰 쪽보다 크다(설계 §6 Q2). 판정은 하지 않는다 — 교차점 N 은 ⑨ 분포와 같이 놓고 사용자가."]
    lines += ["", "원시: `cells.jsonl`(판마다 1행, `discard=true` 는 버림판) · `summary.json`"]
    (out_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


# ── main ────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-levels", default="1,5,12,30,100")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--requests", type=int, default=200)
    ap.add_argument("--warmup", type=int, default=100)
    ap.add_argument("--candidates", default="per-member,batch")
    ap.add_argument("--out", default=None)
    ap.add_argument("--where", default=os.environ.get("MEASURE_WHERE", "local"))
    ap.add_argument("--skip-seed", action="store_true")
    ap.add_argument("--no-restart", action="store_true", help="후보 전환 재기동 없이 지금 뜬 전략으로만(후보 1개)")
    ap.add_argument("--seed", type=int, default=20260915, help="라틴 방격 행 셔플 시드(재현용)")
    args = ap.parse_args()

    levels = [int(x) for x in args.n_levels.split(",")]
    candidates = [c.strip() for c in args.candidates.split(",") if c.strip()]
    if args.no_restart and len(candidates) != 1:
        sys.exit("--no-restart 는 --candidates 하나만")
    out_dir = Path(args.out or REPO_ROOT / "loadtest" / "results" / f"friend-status-fanout-{args.where}-{date.today():%Y-%m-%d}")
    out_dir.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    git = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True, cwd=REPO_ROOT).strip()
    meta = {"where": args.where, "date": f"{date.today():%Y-%m-%d}", "git": git,
            "started": datetime.now().isoformat(timespec="seconds")}
    print(f"[rig] out={out_dir} git={git} levels={levels} candidates={candidates} reps={args.reps}")

    wait_healthy()
    ensure_exercise()
    groups = {}
    for n in levels:
        gid, token = seed_group(n)
        groups[n] = (gid, token)
        print(f"[seed] N={n} group={gid} ok")
    if args.skip_seed:
        pass  # seed_group 은 멱등이라 skip 도 검증만 하고 지나간 셈

    capture_explain(out_dir, groups)
    cells_path = out_dir / "cells.jsonl"
    cells = []
    n_orders = latin_rows(levels, args.reps, rng)
    first = True
    last_strategy = None
    for rep in range(args.reps):
        order = candidates if rep % 2 == 0 else list(reversed(candidates))
        for cand in order:
            if not args.no_restart:
                print(f"[rep {rep + 1}] 후보 {cand} 로 재기동…")
                restart_backend(cand)
                last_strategy = cand
            else:
                cur = current_strategy()
                if cur and cur != cand:
                    sys.exit(f"--no-restart 인데 떠 있는 전략은 {cur}, 요청한 후보는 {cand}")
            # 워밍업 — JIT·커넥션·버퍼풀. 버린다.
            gid, token = groups[levels[len(levels) // 2]]
            for _ in range(args.warmup):
                http_json("GET", f"/groups/{gid}/members/status", token=token)
            for n in n_orders[rep]:
                gid, token = groups[n]
                cell = run_cell(gid, token, n, args.requests, discard=first)
                cell.update({"candidate": cand, "rep": rep + 1, "at": datetime.now().isoformat(timespec="seconds")})
                cells.append(cell)
                with cells_path.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(cell, ensure_ascii=False) + "\n")
                print(f"[rep {rep + 1}][{cand}] N={n:3d} p50={cell['p50_ms']:.2f}ms p95={cell['p95_ms']:.2f}ms "
                      f"sql/req={cell['Com_select_per_req']:.1f} rows(key+prev+next)/req="
                      f"{cell['Handler_read_key_per_req'] + cell['Handler_read_prev_per_req'] + cell['Handler_read_next_per_req']:.1f}"
                      f"{' (버림판)' if first else ''}")
                first = False

    meta["finished"] = datetime.now().isoformat(timespec="seconds")
    summary = summarize(cells, candidates)
    summary["meta"] = meta
    summary["args"] = vars(args)
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_readme(out_dir, args, summary, meta)
    print(f"[rig] 끝 — {out_dir / 'README.md'}")

    # 기본 전략으로 되돌려 둔다 — 다음 사람이 batch 로 뜬 백엔드를 모르고 쓰지 않게.
    if not args.no_restart and last_strategy != "per-member":
        print("[rig] 백엔드를 per-member 로 되돌린다")
        restart_backend("per-member")


if __name__ == "__main__":
    main()
