"""Capture estimated plans (SET SHOWPLAN_TEXT) for arms A1 and B, every (status, window, kind) cell, and summarize which index
each plan uses. Run AFTER the sweep (it switches indexes). usage: python -I gen_plans.py [DB]

Why: the MySQL experiment found the cause was the optimizer choosing (status, start_time) whenever it exists, even where
(start_time, member_id)+filter is faster. The SQL Server counterpart of that question is: does this optimizer choose differently?
"""
import re
import subprocess
import sys
from pathlib import Path

DB = sys.argv[1] if len(sys.argv) > 1 else "sf_idx_bench"
S = r"(localdb)\MSSQLLocalDB"
STATUSES = ["FAILED", "CANCELLED", "IN_PROGRESS", "COMPLETED"]
WINDOWS = [1, 7, 30, 0]
here = Path(__file__).parent
raw = here / "raw"
raw.mkdir(exist_ok=True)


def sqlcmd(*args, inp=None):
    r = subprocess.run(["sqlcmd", "-S", S, "-E", "-b", "-W", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    return r.stdout


anchor = sqlcmd("-d", DB, "-h", "-1", "-Q", "SET NOCOUNT ON; SELECT v FROM dbo.bench_meta WHERE k='anchor_now'").strip().splitlines()[0].strip()
# anchor like 2026-11-23T17:40:12 ; window literal = anchor - N days
from datetime import datetime, timedelta
a = datetime.fromisoformat(anchor)


def query(kind, s, w):
    cond = f"status = '{s}'"
    if w:
        cond += f" AND start_time >= '{(a - timedelta(days=w)).strftime('%Y-%m-%dT%H:%M:%S')}'"
    if kind == "list":
        return f"SELECT TOP 20 id, member_id, start_time, status FROM dbo.sessions_scale WHERE {cond} ORDER BY start_time DESC"
    return f"SELECT COUNT_BIG(*) FROM dbo.sessions_scale WHERE {cond}"


rows = []
for arm in ("A1", "B"):
    parts = [f"USE [{DB}];\nGO\nEXEC dbo.apply_arm '{arm}';\nGO\nSET SHOWPLAN_TEXT ON;\nGO"]
    for kind in ("list", "count"):
        for s in STATUSES:
            for w in WINDOWS:
                parts.append(f"-- CELL {arm} {kind} {s} {w}\n{query(kind, s, w)};\nGO")
    parts.append("SET SHOWPLAN_TEXT OFF;\nGO")
    f = raw / f"plans_{arm}.sql"
    f.write_text("\n".join(parts), encoding="utf-8")
    out = sqlcmd("-i", str(f), "-w", "400")
    (raw / f"plans_{arm}.txt").write_text(out, encoding="utf-8")
    # split output per query: each statement's plan text follows the echoed statement line
    chunks = re.split(r"(?=SELECT (?:TOP 20 id|COUNT_BIG))", out)
    for ch in chunks:
        m = re.match(r"SELECT (TOP 20 id|COUNT_BIG).*?status = '(\w+)'(?: AND start_time >= '([\d\-T:]+)')?", ch, re.S)
        if not m:
            continue
        kind = "list" if m.group(1).startswith("TOP") else "count"
        s = m.group(2)
        w = 0
        if m.group(3):
            w = round((a - datetime.fromisoformat(m.group(3))).total_seconds() / 86400)
        idx = re.findall(r"(Index Seek|Index Scan|Clustered Index Scan|Clustered Index Seek|Key Lookup|RID Lookup)\(OBJECT:\(\[[^\]]+\]\.\[[^\]]+\]\.\[[^\]]+\](?:\.\[([^\]]+)\])?", ch)
        used = []
        for op, name in idx:
            used.append(f"{op}:{name}" if name else op)
        sort = "Sort" if re.search(r"\|--Sort\(|Sort\(TOP|Top N Sort", ch) else ""
        ordered = "ordered-fwd/bwd" if "ORDERED" in ch else ""
        rows.append((arm, kind, s, w, " + ".join(dict.fromkeys(used)) or "?", sort, ordered))

md = ["| arm | kind | status | window | access path | sort |", "|---|---|---|--:|---|---|"]
for r in rows:
    md.append(f"| {r[0]} | {r[1]} | {r[2]} | {r[3] or 'all'} | {r[4]} | {r[5]} |")
(raw / "plans_summary.md").write_text("\n".join(md), encoding="utf-8")
print("\n".join(md))
