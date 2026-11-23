"""Summarize raw/raw.csv (SQL Server sweep) and print markdown tables next to the published MySQL numbers.

MySQL numbers are copied from Shadowfit loadtest/results/admin-selectivity-sweep-2026-09-10/README.md (EC2 m6i.xlarge, MySQL 8.0.46,
buffer pool 2048MB). They are ratios A1/B (> 1: the admin index (status, start_time) wins; < 1: it loses).

usage: python -I summarize.py [raw/raw.csv]
"""
import csv
import statistics as st
import sys
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "raw/raw.csv"
STATUSES = ["FAILED", "CANCELLED", "IN_PROGRESS", "COMPLETED"]
SHARE = {"FAILED": "2%", "CANCELLED": "8%", "IN_PROGRESS": "20%", "COMPLETED": "70%"}
WINDOWS = [1, 7, 30, 0]
WNAME = {1: "1d", 7: "7d", 30: "30d", 0: "all"}

MYSQL_LIST = {  # window -> [FAILED, CANCELLED, IN_PROGRESS, COMPLETED]
    1: [8.93, 2.33, 2.28, 1.24], 7: [21.62, 14.94, 6.32, 1.10],
    30: [14.33, 3.75, 0.89, 0.91], 0: [0.26, 0.10, 0.07, 0.03]}
MYSQL_COUNT = {
    1: [85.0, 40.0, 18.3, 6.14], 7: [183.6, 47.6, 18.4, 5.78],
    30: [191.0, 45.7, 17.8, 5.49], 0: [31.8, 8.17, 3.25, 1.24]}

us = defaultdict(list)       # (arm, status, window, kind) -> [microseconds]
reads = defaultdict(list)
dropped = 0
with open(path, newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if int(r["blk"]) == 0:           # block 0 = warm-up, discarded (same as the MySQL rig)
            continue
        if r["us"] in ("", "NULL"):
            dropped += 1
            continue
        k = (r["arm"], r["status"], int(r["window_d"]), r["kind"])
        us[k].append(int(r["us"]))
        if r["reads"] not in ("", "NULL"):
            reads[k].append(int(r["reads"]))


def med_ms(arm, s, w, kind):
    return st.median(us[(arm, s, w, kind)]) / 1000.0


def spread(arm, s, w, kind):
    v = sorted(us[(arm, s, w, kind)])
    q = st.quantiles(v, n=4)
    return (q[2] - q[0]) / st.median(v) * 100


def ratio_table(kind, mysql):
    print(f"| window \\ status | " + " | ".join(f"{s} {SHARE[s]}" for s in STATUSES) + " |")
    print("|---|" + "--:|" * len(STATUSES))
    for w in WINDOWS:
        cells = []
        for i, s in enumerate(STATUSES):
            a1, b = med_ms("A1", s, w, kind), med_ms("B", s, w, kind)
            x = a1 / b
            flag = "LOSE " if x < 1 else ""
            cells.append(f"{flag}**{x:.2f}x** (MySQL {mysql[w][i]:.2f}x)")
        print(f"| {WNAME[w]} | " + " | ".join(cells) + " |")


print(f"samples per cell: {len(next(iter(us.values())))}  (rows dropped for missing stats: {dropped})\n")
for kind, mysql, title in (("list", MYSQL_LIST, "list (TOP 20 ... ORDER BY start_time DESC)"), ("count", MYSQL_COUNT, "count (COUNT_BIG(*))")):
    print(f"### A1 / B median ratio - {title}\n")
    ratio_table(kind, mysql)
    print()

print("### absolute medians (ms) - SQL Server LocalDB 2019 Express, this laptop. DO NOT compare to the MySQL EC2 ms.\n")
for kind in ("list", "count"):
    for arm in ("A0", "A1", "B"):
        vals = [med_ms(arm, s, w, kind) for s in STATUSES for w in WINDOWS]
        print(f"- {kind:5s} {arm}: min {min(vals):.3f}  median-of-cells {st.median(vals):.3f}  max {max(vals):.3f} ms")
print()

print("### worst cell (all-time, COMPLETED) per arm, ms\n")
for kind in ("list", "count"):
    print(f"- {kind}: " + "  ".join(f"{a}={med_ms(a, 'COMPLETED', 0, kind):.3f}" for a in ("A0", "A1", "B")))
print()

print("### where does B lose to A1 (ratio < 1)?\n")
for kind in ("list", "count"):
    losers = [(s, WNAME[w], med_ms("A1", s, w, kind) / med_ms("B", s, w, kind))
              for s in STATUSES for w in WINDOWS if med_ms("A1", s, w, kind) / med_ms("B", s, w, kind) < 1]
    print(f"- {kind}: " + (", ".join(f"{s}/{w} {x:.2f}x" for s, w, x in losers) if losers else "none"))
print()

print("### noise: max IQR/median over cells (%) per arm\n")
for arm in ("A0", "A1", "B"):
    for kind in ("list", "count"):
        sp = max(spread(arm, s, w, kind) for s in STATUSES for w in WINDOWS)
        print(f"- {arm} {kind}: {sp:.0f}%")
print()

print("### logical reads (pages) median - a few cells\n")
for (s, w) in (("COMPLETED", 0), ("COMPLETED", 30), ("FAILED", 30), ("FAILED", 0)):
    for kind in ("list", "count"):
        print(f"- {s}/{WNAME[w]}/{kind}: " + "  ".join(
            f"{a}={st.median(reads[(a, s, w, kind)]):.0f}" for a in ("A0", "A1", "B") if reads[(a, s, w, kind)]))
