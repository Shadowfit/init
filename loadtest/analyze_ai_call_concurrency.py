"""Spring→AI 동시성 축(4차) 집계 — (팔 × c) 격자에 지연·처리량·CPU/사이클·실패를 놓고 겹침 두 겹으로 판정한다.

설계: docs/decisions/grpc-webclient-concurrency-round.md §3(지표)·§6(판정 규칙).

입력(measure_ai_call_concurrency.sh 가 남긴 것):
  scrape/b{블록}_{팔}_c{c}_{before|after}.txt  — shadowfit.ai.call 버킷(차분은 analyze_ai_call_latency_ab 의 것을 쓴다)
  k6/b{블록}_{팔}_c{c}.json                     — iterations·실패 계수
  cells.tsv                                      — 벽시계·컨테이너 CPU usec 전/후·서킷 상태

판정은 두 겹 다 «겹침» 뿐이다(load-test-strategy.md §2-1). 배수·판정선은 안 쓴다.
  규칙 1 (칸 안):   같은 c 에서 두 팔의 블록값 범위가 안 겹칠 때만 «그 c 에서 차이가 있다».
  규칙 2 (기울기): 어떤 c 의 델타(webclient−grpc) 블록 범위가 c=1 의 델타 범위와 안 겹칠 때만 «c 에 따라 변한다».
  규칙 3:          실패가 있는 칸은 규칙 1·2 에 안 넣고 따로 적는다.

사용: python loadtest/analyze_ai_call_concurrency.py <결과디렉터리>
"""
import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_ai_call_latency_ab import parse, diff, quantile  # noqa: E402

CELL = re.compile(r"b(\d+)_([\w.-]+)_c(\d+)_(before|after)$")
ARM_ORDER = ("grpc", "webclient")   # 델타 = 뒤 − 앞


def load_cells(root):
    """cells.tsv → {(block, arm, c): row dict}. CPU 는 usec → 차분해서 초로."""
    cells = {}
    p = root / "cells.tsv"
    if not p.is_file():
        return cells
    with p.open(encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            key = (int(row["block"]), row["arm"], int(row["c"]))
            cpu = {}
            for name in ("backend", "ai", "nginx", "mysql"):
                b, a = row.get(f"cpu_{name}_before", "na"), row.get(f"cpu_{name}_after", "na")
                cpu[name] = (int(a) - int(b)) / 1e6 if b.isdigit() and a.isdigit() else None
            cells[key] = {"wall_s": float(row["wall_s"]), "k6_rc": row["k6_rc"], "cpu_s": cpu,
                          "cb_before": row.get("cb_before", ""), "cb_after": row.get("cb_after", "")}
    return cells


def load_k6(root, block, arm, c):
    p = root / "k6" / f"b{block}_{arm}_c{c}.json"
    if not p.is_file():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def span(vals):
    return (min(vals), max(vals)) if vals else None


def overlap(a, b):
    return not (a[1] < b[0] or b[1] < a[0])


def fmt_span(s):
    return f"[{s[0]:.3f}, {s[1]:.3f}]" if s else "—"


def main(root):
    root = Path(root)
    scrapes = root / "scrape"
    if not scrapes.is_dir():
        print(f"🔴 {scrapes} 없음"); return 1
    cells = load_cells(root)

    pairs = defaultdict(dict)
    for f in scrapes.glob("*.txt"):
        m = CELL.match(f.stem)
        if m:
            pairs[(int(m.group(1)), m.group(2), int(m.group(3)))][m.group(4)] = f

    # (rpc, arm, c) -> {block: {"n", "mean", "p50", "p99"}}
    lat = defaultdict(dict)
    failures = []          # (block, arm, c, 무엇, 건수)
    for (block, arm, c), pair in sorted(pairs.items()):
        if "before" not in pair or "after" not in pair:
            print(f"⚠️  칸 b{block}/{arm}/c{c} — before/after 짝이 안 맞아 건너뜀"); continue
        for (protocol, rpc, outcome), d in diff(parse(pair["before"]), parse(pair["after"])).items():
            if outcome != "success":
                failures.append((block, arm, c, f"{rpc} outcome={outcome}", int(d["count"])))
                continue
            if protocol != "?" and protocol not in arm:
                print(f"🔴 칸 b{block}/{arm}/c{c} 인데 지표 protocol={protocol} — 팔 귀속 의심")
            lat[(rpc, arm, c)][block] = {
                "n": int(d["count"]), "mean": d["sum"] / d["count"] * 1000,
                "p50": (quantile(d["buckets"], d["count"], 0.50) or 0) * 1000,
                "p99": (quantile(d["buckets"], d["count"], 0.99) or 0) * 1000,
            }
        k6 = load_k6(root, block, arm, c)
        if k6:
            for k in ("start_fail", "start_409", "reattach_bad", "end_bad"):
                if k6.get(k):
                    failures.append((block, arm, c, f"k6 {k}", int(k6[k])))
        cell = cells.get((block, arm, c))
        if cell and cell["k6_rc"] not in ("0", ""):
            failures.append((block, arm, c, f"k6 rc={cell['k6_rc']}", 1))

    tainted = {(b, a, c) for b, a, c, *_ in failures}
    levels = sorted({c for _, _, c in lat})
    blocks_all = sorted({b for v in lat.values() for b in v})

    def judge_grid(title, unit, value_of, lower_is_better=True):
        """value_of(arm, c, block) -> float|None. 규칙 1·2 를 c 마다 적용해 표로 찍는다."""
        print(f"\n## {title} ({unit})")
        head = f"{'c':>4} " + " ".join(f"{a:>28}" for a in ARM_ORDER) + f" {'델타 범위(wc−grpc)':>22}  판정"
        print(head)
        delta_span_by_c = {}
        for c in levels:
            spans, cols = {}, []
            per_block = {}
            for arm in ARM_ORDER:
                vals = {b: value_of(arm, c, b) for b in blocks_all
                        if (b, arm, c) not in tainted and value_of(arm, c, b) is not None}
                per_block[arm] = vals
                spans[arm] = span(list(vals.values()))
                cols.append(f"{fmt_span(spans[arm]):>18} n={len(vals):<2}")
            common = sorted(set(per_block[ARM_ORDER[0]]) & set(per_block[ARM_ORDER[1]]))
            deltas = [per_block[ARM_ORDER[1]][b] - per_block[ARM_ORDER[0]][b] for b in common]
            dspan = span(deltas)
            delta_span_by_c[c] = (dspan, len(deltas))
            # 🔴 블록이 1개면 «범위» 가 점이라 겹칠 수가 없다 — 판정하지 않는다
            #    ([[feedback_measure_design_needs_repeats]]).
            if any(s is None for s in spans.values()):
                verdict = "🟡 한 팔이 비어 판정 없음"
            elif min(len(v) for v in per_block.values()) < 2:
                verdict = "🟡 블록 <2, 판정 없음"
            elif overlap(spans[ARM_ORDER[0]], spans[ARM_ORDER[1]]):
                verdict = "🟡 겹침 — 판별 불가"
            else:
                a, b = spans[ARM_ORDER[0]], spans[ARM_ORDER[1]]
                better = ARM_ORDER[0] if (a[1] < b[0]) == lower_is_better else ARM_ORDER[1]
                verdict = f"✅ 안 겹침 — {better} 우세"
            print(f"{c:>4} " + " ".join(f"{x:>28}" for x in cols) + f" {fmt_span(dspan):>22}  {verdict}")

        # 규칙 2 — 기울기. c=1 의 델타 범위를 닻으로.
        anchor = delta_span_by_c.get(1)
        if not anchor or anchor[0] is None or anchor[1] < 2:
            print("   🟡 c=1 델타 블록이 2개 미만이라 기울기 판정을 하지 않는다.")
            return
        print(f"   기울기(규칙 2) — c=1 델타 범위 {fmt_span(anchor[0])} 과 비교:")
        for c in levels:
            if c == 1:
                continue
            ds, n = delta_span_by_c[c]
            if ds is None or n < 2:
                print(f"     c={c:<3} 🟡 블록 <2, 판정 없음")
            elif overlap(ds, anchor[0]):
                print(f"     c={c:<3} 🟡 {fmt_span(ds)} — c=1 과 겹침 (c 와 무관)")
            else:
                direction = "커짐" if ds[0] > anchor[0][1] else "작아짐"
                print(f"     c={c:<3} ✅ {fmt_span(ds)} — c=1 과 안 겹침 (델타가 {direction})")

    # ── 1. 지연 — reattach 주 판정, start 보조, stop 은 부산물(§3-3) ──────────────
    for rpc, role in (("reattach", "주 판정"), ("start", "보조 — fire-and-forget 콜백"), ("stop", "부산물 — 아웃박스가 직렬화, 판정 밖")):
        if not any(r == rpc for r, _, _ in lat):
            continue
        print(f"\n# {rpc} ({role})")
        for b in blocks_all:
            row = []
            for c in levels:
                for arm in ARM_ORDER:
                    v = lat.get((rpc, arm, c), {}).get(b)
                    row.append(f"{arm[:2]}c{c}={v['p50']:.2f}({v['n']})" if v else f"{arm[:2]}c{c}=—")
            print(f"  블록 {b}: " + "  ".join(row))
        # 평균도 같이 — 2·3차의 c=1 닻은 «블록 평균» 범위였다(analyze_ai_call_latency_ab). p50 만 있으면 못 잇는다.
        judge_grid(f"{rpc} 평균", "ms", lambda arm, c, b, rpc=rpc: lat.get((rpc, arm, c), {}).get(b, {}).get("mean"))
        judge_grid(f"{rpc} p50", "ms", lambda arm, c, b, rpc=rpc: lat.get((rpc, arm, c), {}).get(b, {}).get("p50"))
        judge_grid(f"{rpc} p99", "ms", lambda arm, c, b, rpc=rpc: lat.get((rpc, arm, c), {}).get(b, {}).get("p99"))

    # ── 2. 처리량 — 사이클/초 (k6 iterations ÷ rig 벽시계) ─────────────────────────
    def throughput(arm, c, b):
        cell, k6 = cells.get((b, arm, c)), load_k6(root, b, arm, c)
        if not cell or not k6 or not k6.get("iterations") or cell["wall_s"] <= 0:
            return None
        return k6["iterations"] / cell["wall_s"]
    if cells:
        print("\n# 처리량")
        judge_grid("사이클/초", "cycles/s", throughput, lower_is_better=False)

    # ── 3. CPU-초/사이클 — 컨테이너별. 서버 포화에도 살아남는 지표(§3-1) ──────────────
    def cpu_per_cycle(name):
        def f(arm, c, b):
            cell, k6 = cells.get((b, arm, c)), load_k6(root, b, arm, c)
            if not cell or not k6 or not k6.get("iterations") or cell["cpu_s"].get(name) is None:
                return None
            return cell["cpu_s"][name] / k6["iterations"] * 1000   # ms of CPU per cycle
        return f
    if cells:
        print("\n# CPU-ms/사이클 (cgroup 차분 ÷ 사이클)")
        judge_grid("backend CPU/사이클 — 클라이언트 구조의 대가가 여기 나타난다", "cpu-ms/cycle", cpu_per_cycle("backend"))
        judge_grid("ai CPU/사이클 — 혼입 확인: 두 팔에서 서버 몫이 같은가", "cpu-ms/cycle", cpu_per_cycle("ai"))
        judge_grid("nginx CPU/사이클 — webclient 팔만 거친다", "cpu-ms/cycle", cpu_per_cycle("nginx"))
        # AI 포화 여부 — 칸 벽시계 대비 AI CPU 가 워커 수(코어) 근처면 서버 지배 구간이다.
        print("\n   AI 컨테이너 CPU 점유(코어 수 환산, 벽시계 대비) — 워커 3 이면 3 근처가 포화:")
        for c in levels:
            parts = []
            for arm in ARM_ORDER:
                vals = [cells[(b, arm, c)]["cpu_s"]["ai"] / cells[(b, arm, c)]["wall_s"]
                        for b in blocks_all if (b, arm, c) in cells and cells[(b, arm, c)]["cpu_s"].get("ai") is not None
                        and cells[(b, arm, c)]["wall_s"] > 0]
                parts.append(f"{arm} {fmt_span(span(vals))}")
            print(f"     c={c:<3} " + " · ".join(parts))

    # ── 4. 실패 — 규칙 3. 평균에 안 섞고 여기 적는다 ───────────────────────────────
    print("\n# 실패 계수 (규칙 3 — 위 판정에서 제외된 칸)")
    if not failures:
        print("  없음")
    for b, arm, c, what, n in sorted(failures):
        cb = cells.get((b, arm, c), {})
        print(f"  b{b}/{arm}/c{c}: {what} {n}건  서킷 전={cb.get('cb_before', '?')} 후={cb.get('cb_after', '?')}")
    if tainted:
        print(f"  → 제외된 칸 {len(tainted)}개: " + ", ".join(f"b{b}/{a}/c{c}" for b, a, c in sorted(tainted)))

    print("\n(절대값은 이 동거 무대의 값이다. 결론 문장은 설계 §6 의 셋 중 하나로만 — 여기서는 안 고른다.)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
