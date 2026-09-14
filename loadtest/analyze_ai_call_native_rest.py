"""5차(네이티브 REST 팔) 집계 — 팔 4개(grpc · webclient · webclient-native · webclient-nested)를
(팔 × c) 격자에 놓고, 세 뺄셈(B−C · C−A · C−D)을 겹침 규칙으로 판정한다.

설계: docs/decisions/grpc-webclient-native-rest-round.md §3(지표)·§6(판정 규칙).
입력은 4차 rig(measure_ai_call_concurrency.sh)가 남기는 것과 같다 — scrape/·k6/·cells.tsv. 파싱은
analyze_ai_call_concurrency.py 의 것을 그대로 가져다 쓴다(팔 2개 고정인 판정부만 여기서 다시 짠다).

판정(§6) — 배수·판정선 없음, 겹침만:
  규칙 1: 같은 c 에서 두 팔의 블록값 범위가 안 겹칠 때만 «다르다».
    B−C = 겹(proto 재조립 + 서비서 호출)의 대가 · C−A = REST 라서 내는 값 · C−D = 이중 인코딩의 대가
  규칙 4: 실패·재시작이 있는 칸은 빼고 따로 적는다.
주 판정은 ai CPU-ms/재부착. 처리량(c=8)·지연은 보조.

사용: python loadtest/analyze_ai_call_native_rest.py <결과디렉터리>
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_ai_call_latency_ab import parse, diff, quantile  # noqa: E402
from analyze_ai_call_concurrency import load_cells, load_k6, span, overlap, fmt_span  # noqa: E402

CELL = re.compile(r"b(\d+)_([\w.-]+)_c(\d+)_(before|after)$")
A, B, C, D = "grpc", "webclient", "webclient-native", "webclient-nested"
ARMS = (A, B, C, D)
SHORT = {A: "A·grpc", B: "B·mirror", C: "C·native", D: "D·nested"}
# (이름, 뒤, 앞) — 델타 = 뒤 − 앞
CONTRASTS = (
    ("B−C 겹의 대가", B, C),
    ("C−A REST 고유", C, A),
    ("C−D 이중 인코딩", C, D),
    ("B−A (4차와 이어 읽기)", B, A),
)


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

    lat = defaultdict(dict)   # (rpc, arm, c) -> {block: {...}}
    failures = []
    for (block, arm, c), pair in sorted(pairs.items()):
        if "before" not in pair or "after" not in pair:
            print(f"⚠️  칸 b{block}/{arm}/c{c} — before/after 짝이 안 맞아 건너뜀"); continue
        for (protocol, rpc, outcome), d in diff(parse(pair["before"]), parse(pair["after"])).items():
            if outcome != "success":
                failures.append((block, arm, c, f"{rpc} outcome={outcome}", int(d["count"]))); continue
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

    # 재시작 칸(#731 의 rig 구멍을 메운 열) — cells.tsv 에 restarts_before/after 가 있을 때만.
    restarts = {}
    p = root / "cells.tsv"
    if p.is_file():
        import csv
        with p.open(encoding="utf-8") as f:
            for row in csv.DictReader(f, delimiter="\t"):
                rb, ra = row.get("restarts_before", ""), row.get("restarts_after", "")
                if rb and ra and rb != ra:
                    key = (int(row["block"]), row["arm"], int(row["c"]))
                    restarts[key] = f"{rb} → {ra}"
                    failures.append((*key, f"컨테이너 재시작 {rb} → {ra}", 1))

    tainted = {(b, a, c) for b, a, c, *_ in failures}
    levels = sorted({c for _, _, c in lat})
    blocks_all = sorted({b for v in lat.values() for b in v})
    arms_present = [a for a in ARMS if any(arm == a for _, arm, _ in lat)]

    def calls(k6):
        if not k6:
            return None
        return k6.get("reattach_ok") if k6.get("reattach_ok") is not None else k6.get("iterations")

    def judge(title, unit, value_of, lower_is_better=True):
        print(f"\n## {title} ({unit})")
        print(f"{'c':>4} " + " ".join(f"{SHORT[a]:>26}" for a in arms_present))
        per = {}
        for c in levels:
            cols = []
            for arm in arms_present:
                vals = {b: value_of(arm, c, b) for b in blocks_all
                        if (b, arm, c) not in tainted and value_of(arm, c, b) is not None}
                per[(arm, c)] = vals
                cols.append(f"{fmt_span(span(list(vals.values()))):>20} n={len(vals):<2}")
            print(f"{c:>4} " + " ".join(f"{x:>26}" for x in cols))
        for name, hi, lo in CONTRASTS:
            if hi not in arms_present or lo not in arms_present:
                continue
            print(f"   {name}:")
            for c in levels:
                vh, vl = per.get((hi, c), {}), per.get((lo, c), {})
                common = sorted(set(vh) & set(vl))
                deltas = [vh[b] - vl[b] for b in common]
                sh, sl = span(list(vh.values())), span(list(vl.values()))
                if not sh or not sl:
                    verdict = "🟡 한 팔이 비어 판정 없음"
                elif min(len(vh), len(vl)) < 2:
                    verdict = "🟡 블록 <2, 판정 없음"   # 범위가 점이면 겹칠 수 없다 — 판정 안 함
                elif overlap(sh, sl):
                    verdict = "🟡 겹침 — 판별 불가"
                else:
                    better = lo if (sl[1] < sh[0]) == lower_is_better else hi
                    verdict = f"✅ 안 겹침 — {SHORT[better]} 우세"
                print(f"     c={c:<3} 델타 {fmt_span(span(deltas)):>20} (블록 {len(deltas)})  {verdict}")

    # 1. 지연 — 보조
    for rpc, role in (("reattach", "보조 — c=1 델타. 포화 구간은 큐잉 지배"), ("start", "보조 — fire-and-forget 콜백")):
        if not any(r == rpc for r, _, _ in lat):
            continue
        print(f"\n# {rpc} ({role})")
        judge(f"{rpc} 평균", "ms", lambda arm, c, b, rpc=rpc: lat.get((rpc, arm, c), {}).get(b, {}).get("mean"))
        judge(f"{rpc} p50", "ms", lambda arm, c, b, rpc=rpc: lat.get((rpc, arm, c), {}).get(b, {}).get("p50"))

    # 2. 처리량
    def throughput(arm, c, b):
        cell, k6 = cells.get((b, arm, c)), load_k6(root, b, arm, c)
        if not cell or not calls(k6) or cell["wall_s"] <= 0:
            return None
        return calls(k6) / cell["wall_s"]
    if cells:
        print("\n# 처리량 (포화 구간 c≥8 이 판정 자리 — 4차 −15% 가 C·D 에서 어디로 가나)")
        judge("재부착/초", "calls/s", throughput, lower_is_better=False)

    # 3. CPU-ms/재부착 — ai 가 주 판정
    def cpu_per_call(name):
        def f(arm, c, b):
            cell, k6 = cells.get((b, arm, c)), load_k6(root, b, arm, c)
            if not cell or not calls(k6) or cell["cpu_s"].get(name) is None:
                return None
            return cell["cpu_s"][name] / calls(k6) * 1000
        return f
    if cells:
        print("\n# CPU-ms/재부착 (cgroup 차분 ÷ 재부착 건수)")
        judge("★ ai CPU/재부착 — 주 판정(§6 규칙 1)", "cpu-ms/call", cpu_per_call("ai"))
        judge("nginx CPU/재부착 — B·C·D 에서 같아야 한다(다르면 D 의 본문 크기가 홉에 미친 것)", "cpu-ms/call", cpu_per_call("nginx"))
        judge("backend CPU/재부착 — 4차와 같이 판별 불가가 예상 자리", "cpu-ms/call", cpu_per_call("backend"))
        print("\n   AI 컨테이너 CPU 점유(코어 수 환산, 벽시계 대비) — 워커 3 이면 3 근처가 포화:")
        for c in levels:
            parts = []
            for arm in arms_present:
                vals = [cells[(b, arm, c)]["cpu_s"]["ai"] / cells[(b, arm, c)]["wall_s"]
                        for b in blocks_all if (b, arm, c) in cells and cells[(b, arm, c)]["cpu_s"].get("ai") is not None
                        and cells[(b, arm, c)]["wall_s"] > 0]
                parts.append(f"{SHORT[arm]} {fmt_span(span(vals))}")
            print(f"     c={c:<3} " + " · ".join(parts))

    # 4. 실패·재시작 — 규칙 4
    print("\n# 실패·재시작 계수 (규칙 4 — 위 판정에서 제외된 칸)")
    if not failures:
        print("  없음")
    for b, arm, c, what, n in sorted(failures):
        cb = cells.get((b, arm, c), {})
        print(f"  b{b}/{arm}/c{c}: {what} {n}건  서킷 전={cb.get('cb_before', '?')} 후={cb.get('cb_after', '?')}")
    if tainted:
        print(f"  → 제외된 칸 {len(tainted)}개: " + ", ".join(f"b{b}/{a}/c{c}" for b, a, c in sorted(tainted)))

    print("\n(절대값은 이 동거 무대의 값이고 calib cpu 를 병기해야 한다(인용 규칙 ㉠). 결론 문장은 설계 §6 의 넷 중"
          " 하나로만 — 어느 것인지는 ai CPU/재부착의 B−C · C−A 판정을 그대로 읽는다. 여기서는 안 고른다.)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
