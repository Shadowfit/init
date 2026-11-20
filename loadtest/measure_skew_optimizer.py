#!/usr/bin/env python3
"""
치우친 분포에서 옵티마이저 추정·계획 선택 — M0~M3 측정 하니스.

문서: docs/decisions/skewed-distribution-optimizer-estimates.md (§6, §7 confirm 2026-09-23)
시더: loadtest/seed/gen_skew_seed.py

## 무엇을 재는가

데이터셋(분포) × 통계 팔(S0~S3) 마다 대상 쿼리의 EXPLAIN ANALYZE 를 떠서
  · 드라이빙 테이블·인덱스 (계획이 무엇인가)
  · 접근 노드마다 추정 rows vs 실제 rows → q-error = max(추정/실제, 실제/추정)
  · Handler_read_* 합 (그 계획이 실제로 얼마나 읽었나)
를 남긴다. M3 은 S1 설정에서 ANALYZE 를 여러 번 돌려 카디널리티·계획이 흔들리는지 본다.

## 데이터셋 (문서 §7 confirm)

  T1 용 (주간 JSON_TABLE):  X1 {균일 7, 약함 50, 강함 365} × X2 {uni 25%×4, skew 70/20/8/2}, W=7
  T3·T4 용 (대시보드·관리자 목록): X2 {uni, skew}, 최대 F=7, W=0 (전부 52주에 흩음)
  모두 평균 F=7, 세션 약 10만, R=30.

## 통계 팔 (문서 §5)

  S0 적재 직후 그대로 — 단 InnoDB 는 표의 10% 이상이 바뀌면 백그라운드로 통계를 다시 잰다
     (innodb_stats_auto_recalc). 그래서 S0 는 «아무것도 안 함» 이지 «통계 없음» 이 아니다.
     팔마다 mysql.innodb_index_stats 스냅샷을 같이 남겨 무엇을 보고 골랐는지 기록한다.
  S1 ANALYZE TABLE
  S2 S1 + 히스토그램 (exercise_sessions.status·start_time·member_id, session_reports.member_id)
  S3 히스토그램 제거, STATS_SAMPLE_PAGES = 65535(허용 최대 = 이 크기의 표에선 사실상 전수) + ANALYZE
     — «표본을 최대로 키우면 추정이 좋아지나» 의 상한. 중간값은 근거가 없어 두지 않는다.

## 판정 (문서 §6)

판정선을 두지 않는다. q-error 는 보고만 하고, 판정은 «계획이 바뀌었나» 와 «바뀐 계획이 Handler 를
더 읽나» 로만 한다. 시간(actual time)은 적어 두지만 **이 라운드는 시간을 판정에 쓰지 않는다**
— 반복·라틴 방격이 없고 로컬 박스다(M4 는 계획이 뒤집힌 셀만 따로).

## 사용

    python loadtest/measure_skew_optimizer.py            # 시딩 + 측정 전부
    SKIP_SEED=1 python loadtest/measure_skew_optimizer.py # 이미 적재된 DB 로 측정만 (⚠️ 그러면 S0 는 «적재 직후» 가 아니다)
    REPARSE=1 python loadtest/measure_skew_optimizer.py   # raw/ 만 다시 파싱
"""
import os
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEED = ROOT / "seed" / "gen_skew_seed.py"
OUT = ROOT / "results" / f"skew-optimizer-{datetime.now():%Y-%m-%d}"
CONTAINER = os.environ.get("CONTAINER", "shadowfit-mysql")
PW = os.environ.get("MYSQL_PW", "1234")
SKIP_SEED = os.environ.get("SKIP_SEED") == "1"
M3_REPEATS = int(os.environ.get("M3_REPEATS", "5"))
TOTAL = int(os.environ.get("TOTAL_SESSIONS", "100000"))

MIX = {
    "uni": "COMPLETED=25,IN_PROGRESS=25,CANCELLED=25,FAILED=25",
    "skew": "COMPLETED=70,IN_PROGRESS=20,CANCELLED=8,FAILED=2",  # 09-10 관리자 선택도 스윕과 같음(가정값)
}
DATASETS = (
    [dict(db=f"skew_t1_f{f}_{x2}", target="T1", max_f=f, week=7, mix=x2) for f in (7, 50, 365) for x2 in ("uni", "skew")]
    + [dict(db=f"skew_t34_{x2}", target="T34", max_f=7, week=0, mix=x2) for x2 in ("uni", "skew")]
)
if os.environ.get("ONLY"):
    DATASETS = [d for d in DATASETS if d["db"] in os.environ["ONLY"].split(",")]

WEEK_FROM, WEEK_TO = "2025-10-01 00:00:00", "2025-10-08 00:00:00"
ANCHOR = "2025-10-01 00:00:00"  # T4 기간 필터의 기준 시각 (09-10 rig 은 NOW() — 이 시드는 날짜가 고정이라 바꿨다)
STATUSES = ("FAILED", "CANCELLED", "IN_PROGRESS", "COMPLETED")
WINDOWS = (1, 7, 30, 0)  # 일, 0 = 기간 제한 없음 (09-10 과 같은 격자)


def mysql(sql, db=None):
    cmd = ["docker", "exec", "-i", CONTAINER, "mysql", "-uroot", f"-p{PW}", "-N", "-B", "-r",
           "--default-character-set=utf8mb4"]
    if db:
        cmd.append(db)
    p = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    err = p.stderr.decode("utf-8", "replace")
    err = "\n".join(l for l in err.splitlines() if "Using a password" not in l)
    if p.returncode != 0:
        raise RuntimeError(f"mysql 실패 (db={db}):\n{err}")
    return p.stdout.decode("utf-8", "replace")


# ── 쿼리 ─────────────────────────────────────────────────────────────────────
JT2 = ("CROSS JOIN JSON_TABLE(r.detailed_analysis, '$.repTrend[*]' COLUMNS (rep_number INT PATH '$.repNumber', "
       "sync_rate DOUBLE PATH '$.syncRate')) jt")
JT3 = "CROSS JOIN JSON_TABLE(r.detailed_analysis, '$' COLUMNS (worst_rep INT PATH '$.worstSection.repNumber')) jt"
WK = f"s.start_time >= '{WEEK_FROM}' AND s.start_time < '{WEEK_TO}'"


def t1_queries(members):
    q = {}
    for label, mid in members.items():
        # cur = ㄴ-3 이전 모양(r.member_id), n3 = 현행 코드(s.member_id + s.status='COMPLETED')
        q[f"T1.Q2cur.{label}"] = (f"SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*) FROM session_reports r "
                                  f"JOIN exercise_sessions s ON s.id = r.session_id {JT2} WHERE r.member_id = {mid} "
                                  f"AND {WK} GROUP BY jt.rep_number ORDER BY jt.rep_number")
        q[f"T1.Q2n3.{label}"] = (f"SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*) FROM session_reports r "
                                 f"JOIN exercise_sessions s ON s.id = r.session_id {JT2} WHERE s.member_id = {mid} "
                                 f"AND s.status = 'COMPLETED' AND {WK} GROUP BY jt.rep_number ORDER BY jt.rep_number")
        q[f"T1.Q3cur.{label}"] = (f"SELECT jt.worst_rep, COUNT(*) FROM session_reports r JOIN exercise_sessions s "
                                  f"ON s.id = r.session_id {JT3} WHERE r.member_id = {mid} AND {WK} "
                                  f"AND jt.worst_rep IS NOT NULL GROUP BY jt.worst_rep "
                                  f"ORDER BY COUNT(*) DESC, jt.worst_rep ASC")
        q[f"T1.Q3n3.{label}"] = (f"SELECT jt.worst_rep, COUNT(*) FROM session_reports r JOIN exercise_sessions s "
                                 f"ON s.id = r.session_id {JT3} WHERE s.member_id = {mid} AND s.status = 'COMPLETED' "
                                 f"AND {WK} AND jt.worst_rep IS NOT NULL GROUP BY jt.worst_rep "
                                 f"ORDER BY COUNT(*) DESC, jt.worst_rep ASC")
    return q


def t34_queries():
    q = {
        # 대시보드 a — SessionRepository.countStartedBetween (하루치)
        "T3.a.day": ("SELECT COUNT(*) FROM exercise_sessions WHERE start_time >= '2025-09-30 00:00:00' "
                     "AND start_time < '2025-10-01 00:00:00'"),
        # 대시보드 b — SessionRepository.countGroupedByStatus
        "T3.b.status": "SELECT status, COUNT(*) FROM exercise_sessions GROUP BY status",
    }
    for st in STATUSES:
        for w in WINDOWS:
            cond = f"status = '{st}'" + ("" if w == 0 else f" AND start_time >= TIMESTAMP('{ANCHOR}') - INTERVAL {w} DAY")
            q[f"T4.list.{st}.w{w}"] = (f"SELECT id, member_id, start_time, status FROM exercise_sessions "
                                       f"WHERE {cond} ORDER BY start_time DESC LIMIT 20")
            # 반사실: 09-10 에서 흔한 상태·넓은 기간일 때 더 좋았던 쪽을 강제
            q[f"T4.listForceTime.{st}.w{w}"] = (f"SELECT id, member_id, start_time, status FROM exercise_sessions "
                                                f"FORCE INDEX (idx_session_starttime_member) WHERE {cond} "
                                                f"ORDER BY start_time DESC LIMIT 20")
            q[f"T4.count.{st}.w{w}"] = f"SELECT COUNT(*) FROM exercise_sessions WHERE {cond}"
    return q


def batch_sql(queries):
    parts = []
    for name, sql in queries.items():
        parts.append(f"SELECT '##Q|{name}';")
        parts.append(f"EXPLAIN ANALYZE {sql};")
        parts.append("FLUSH STATUS;")
        parts.append(f"{sql};")  # 원래 쿼리 그대로 — derived 로 감싸면 계획이 바뀔 수 있다
        parts.append("SHOW SESSION STATUS LIKE 'Handler_read%';")
    parts.append("SELECT '##END';")
    return "\n".join(parts)


def stats_snapshot_sql(db):
    return f"""
SELECT '##STATS';
SELECT table_name, index_name, stat_name, stat_value, sample_size, last_update
  FROM mysql.innodb_index_stats
 WHERE database_name = '{db}' AND table_name IN ('exercise_sessions','session_reports')
   AND stat_name IN ('n_diff_pfx01','n_diff_pfx02','n_leaf_pages')
 ORDER BY table_name, index_name, stat_name;
SELECT '##HIST';
SELECT TABLE_NAME, COLUMN_NAME, HISTOGRAM->>'$."histogram-type"', JSON_LENGTH(HISTOGRAM->'$.buckets')
  FROM information_schema.COLUMN_STATISTICS WHERE SCHEMA_NAME = '{db}';
SELECT '##ENDSTATS';
"""


HIST_ON = "ANALYZE TABLE exercise_sessions UPDATE HISTOGRAM ON status, start_time, member_id;\n" \
          "ANALYZE TABLE session_reports UPDATE HISTOGRAM ON member_id;\n"
HIST_OFF = "ANALYZE TABLE exercise_sessions DROP HISTOGRAM ON status, start_time, member_id;\n" \
           "ANALYZE TABLE session_reports DROP HISTOGRAM ON member_id;\n"
ANALYZE = "ANALYZE TABLE exercise_sessions, session_reports;\n"


# ── 파싱 ─────────────────────────────────────────────────────────────────────
NODE = re.compile(r"^(?P<ind>\s*)-> (?P<txt>.*?)\s+\(cost=[^ ]+ rows=(?P<est>[\d.e+]+)\)"
                  r"(?: \(actual time=(?P<t0>[\d.]+)\.\.(?P<t1>[\d.]+) rows=(?P<act>[\d.e+]+) loops=(?P<loops>\d+)\))?")
ACCESS = re.compile(r"(?:scan|lookup|seek)[^()]*? on (?P<tbl>\w+)(?: using (?P<idx>\w+))?", re.I)


def parse_section(lines):
    """EXPLAIN ANALYZE 트리 + Handler → dict.

    추정 rows 가 붙은 노드를 전부 모은다. 접근 노드(인덱스 lookup·range)는 index dive 라 대체로 정확하고,
    틀리는 곳은 그 위의 필터·조인 노드다 — 스모크(09-23)에서 조인 추정 130 vs 실제 6 을 접근 노드만 보고 놓쳤다.
    """
    nodes, handler = [], 0
    top_ms = None
    for ln in lines:
        m = NODE.match(ln)
        if m:
            if top_ms is None and m["t1"]:
                top_ms = float(m["t1"])
            if m["act"] is None:
                continue
            est, act = float(m["est"]), float(m["act"])
            # q-error 는 루프 합계로 잰다 — 루프당 값은 0.5·0.02 처럼 1 아래라 하한 1 에 가려진다.
            # 실제 0행이면 무한대가 되므로 합계에 1 을 하한으로 둔다.
            e1, a1 = max(est * int(m["loops"]), 1.0), max(act * int(m["loops"]), 1.0)
            a = ACCESS.search(m["txt"])
            if a:
                kind, tbl, idx = "access", a["tbl"], a["idx"] or "-"
            else:
                kind = m["txt"].split(":")[0].split(" (")[0].strip()[:28]
                tbl, idx = "", ""
            nodes.append(dict(kind=kind, tbl=tbl, idx=idx, est=est, act=act, loops=int(m["loops"]),
                              qerr=max(e1 / a1, a1 / e1)))
            continue
        parts = ln.split("\t")
        if len(parts) == 2 and parts[0].startswith("Handler_read"):
            handler += int(parts[1])
    return dict(nodes=nodes, handler=handler, top_ms=top_ms)


def split_sections(text):
    out, cur, name = {}, [], None
    for ln in text.splitlines():
        if ln.startswith("##Q|") or ln == "##END":
            if name:
                out[name] = parse_section(cur)
            name, cur = (ln[4:] if ln.startswith("##Q|") else None), []
        elif name:
            cur.append(ln)
    return out


# ── 실행 ─────────────────────────────────────────────────────────────────────
def seed(ds):
    args = [sys.executable, str(SEED), "--db", ds["db"], "--total-sessions", str(TOTAL), "--max-f", str(ds["max_f"]),
            "--mean-f", "7", "--week-sessions", str(ds["week"]), "--reps", "30", "--status-mix", MIX[ds["mix"]],
            "--rng-seed", "1"]
    p = subprocess.run(args, capture_output=True)
    log = p.stderr.decode("utf-8", "replace")
    if p.returncode != 0:
        raise RuntimeError(f"시드 실패 {ds['db']}\n{log}")
    return p.stdout.decode("utf-8"), log


def members_from_log(log):
    m = {}
    for label, key in (("heavy", "헤비"), ("mid", "중간"), ("light", "라이트")):
        g = re.search(rf"{key} id=(\d+) \(F=(\d+)\)", log)
        m[label] = int(g[1])
    return m


def run_arm(ds, arm, queries, raw_dir):
    db = ds["db"]
    prep = {"S0": "", "S1": ANALYZE, "S2": ANALYZE + HIST_ON,
            "S3": HIST_OFF + "ALTER TABLE exercise_sessions STATS_SAMPLE_PAGES = 65535;\n"
                  "ALTER TABLE session_reports STATS_SAMPLE_PAGES = 65535;\n" + ANALYZE}[arm]
    text = mysql(prep + stats_snapshot_sql(db) + batch_sql(queries), db)
    (raw_dir / f"{db}__{arm}.txt").write_text(text, encoding="utf-8")
    return split_sections(text)


def run_m3(ds, queries, raw_dir):
    """S1 설정(표본 기본값)에서 ANALYZE 반복 → 카디널리티·계획 흔들림."""
    db = ds["db"]
    keys = [k for k in queries if k.startswith(("T1.Q2cur", "T3.a", "T4.list.COMPLETED"))]
    rows = []
    for i in range(M3_REPEATS):
        sql = ANALYZE + stats_snapshot_sql(db) + "".join(
            f"SELECT '##P|{k}';\nEXPLAIN FORMAT=TREE {queries[k]};\n" for k in keys) + "SELECT '##END';\n"
        text = mysql(sql, db)
        (raw_dir / f"{db}__M3_{i + 1}.txt").write_text(text, encoding="utf-8")
        card = {}
        for ln in text.splitlines():
            p = ln.split("\t")
            if len(p) >= 5 and p[2] in ("n_diff_pfx01", "n_diff_pfx02"):
                card[f"{p[0]}.{p[1]}.{p[2]}"] = p[3]
        plans, name = {}, None
        for ln in text.splitlines():
            if ln.startswith("##P|"):
                name = ln[4:]
            elif name and "-> " in ln:
                a = ACCESS.search(ln)
                if a and name not in plans:
                    plans[name] = f"{a['tbl']}({a['idx'] or '-'})"
        rows.append((i + 1, card, plans))
    return rows


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    raw = OUT / "raw"
    raw.mkdir(exist_ok=True)
    env = mysql("SELECT VERSION(), @@innodb_buffer_pool_size, @@innodb_stats_persistent, "
                "@@innodb_stats_auto_recalc, @@innodb_stats_persistent_sample_pages, @@eq_range_index_dive_limit;")
    (OUT / "env.txt").write_text(env, encoding="utf-8")
    print("환경:", env.strip())

    summary = [HEADER]
    m3_lines = ["dataset\trep\tcardinality\tplans"]
    for ds in DATASETS:
        db = ds["db"]
        print(f"\n== {db}")
        if not SKIP_SEED:
            sql, log = seed(ds)
            (OUT / "seed").mkdir(exist_ok=True)
            (OUT / "seed" / f"{db}.log").write_text(log, encoding="utf-8")
            print(log.strip())
            t0 = datetime.now()
            mysql(sql)
            print(f"   적재 {(datetime.now() - t0).seconds}s")
        log = (OUT / "seed" / f"{db}.log").read_text(encoding="utf-8")
        queries = t1_queries(members_from_log(log)) if ds["target"] == "T1" else t34_queries()
        for arm in ("S0", "S1"):
            res = run_arm(ds, arm, queries, raw)
            summary += rows_of(ds, arm, res)
            print(f"   {arm} 완료 ({len(res)} 쿼리)")
        for rep, card, plans in run_m3(ds, queries, raw):
            m3_lines.append(f"{db}\t{rep}\t{card}\t{plans}")
        print(f"   M3 {M3_REPEATS}회 완료")
        for arm in ("S2", "S3"):
            res = run_arm(ds, arm, queries, raw)
            summary += rows_of(ds, arm, res)
            print(f"   {arm} 완료 ({len(res)} 쿼리)")
        (OUT / "summary.tsv").write_text("\n".join(summary) + "\n", encoding="utf-8")
        (OUT / "m3.tsv").write_text("\n".join(m3_lines) + "\n", encoding="utf-8")
    print(f"\n결과: {OUT}")


def rows_of(ds, arm, res):
    rows = []
    for q, r in res.items():
        nodes = r["nodes"]
        acc = [n for n in nodes if n["kind"] == "access"]
        drv = f"{acc[0]['tbl']}({acc[0]['idx']})" if acc else "?"
        desc = " ; ".join(
            (f"{n['tbl']}:{n['idx']}" if n["kind"] == "access" else n["kind"])
            + f" {n['est']:g}/{n['act']:g}x{n['loops']} q{n['qerr']:.1f}" for n in nodes)
        mq_acc = max((n["qerr"] for n in acc), default=0)
        mq_all = max((n["qerr"] for n in nodes), default=0)
        rows.append("\t".join([ds["db"], ds["target"], arm, q, drv, f"{mq_acc:.1f}", f"{mq_all:.1f}",
                               str(r["handler"]), str(r["top_ms"]), desc]))
    return rows


def reparse():
    """raw/ 만 다시 읽어 summary.tsv 를 다시 만든다 (DB 를 안 건드림)."""
    summary = [HEADER]
    for ds in DATASETS:
        for arm in ("S0", "S1", "S2", "S3"):
            f = OUT / "raw" / f"{ds['db']}__{arm}.txt"
            if f.exists():
                summary += rows_of(ds, arm, split_sections(f.read_text(encoding="utf-8")))
    (OUT / "summary.tsv").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print(f"재파싱 {len(summary) - 1} 행 → {OUT / 'summary.tsv'}")


HEADER = "\t".join(["dataset", "target", "arm", "query", "driving", "max_q_access", "max_q_all", "handler",
                    "top_ms", "nodes(est/act x loops q)"])


if __name__ == "__main__":
    reparse() if os.environ.get("REPARSE") == "1" else main()
