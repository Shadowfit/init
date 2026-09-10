"""Spring→AI 왕복 지연 A/B 집계 — 스크레이프 차분으로 «블록별 분포» 를 만든다.

설계: docs/decisions/grpc-webclient-production-client-round.md §5.

왜 차분인가: shadowfit.ai.call 은 누적 타이머라 어느 시점의 스크레이프 하나만으로는
«그 블록에서 무슨 일이 있었나» 를 못 본다. 블록 시작/끝 스크레이프의 **버킷·합·개수를 각각
빼서** 그 블록만의 분포를 만든다.

판정은 여기서 «승패» 를 찍지 않는다 — load-test-strategy.md §2-1 규칙대로 **팔별 블록값
범위가 겹치는지** 만 보고, 겹치면 «판별 불가» 로 적는다. 배수 기준은 안 쓴다.

사용: python loadtest/analyze_ai_call_latency_ab.py <결과디렉터리>
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

LINE = re.compile(r'^shadowfit_ai_call_seconds(_bucket|_count|_sum)?\{([^}]*)\}\s+([0-9.eE+-]+)')


def parse(path):
    """스크레이프 파일 → {(protocol, rpc, outcome): {"buckets": {le: v}, "count": v, "sum": v}}"""
    out = defaultdict(lambda: {"buckets": {}, "count": 0.0, "sum": 0.0})
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE.match(line)
        if not m:
            continue
        kind, tags, value = m.group(1), m.group(2), float(m.group(3))
        t = dict(re.findall(r'(\w+)="([^"]*)"', tags))
        # quantile 계열(누적 백분위)은 차분이 안 되므로 버린다 — 버킷만 쓴다.
        if "quantile" in t:
            continue
        key = (t.get("protocol", "?"), t.get("rpc", "?"), t.get("outcome", "?"))
        if kind == "_bucket":
            out[key]["buckets"][float(t["le"])] = value
        elif kind == "_count":
            out[key]["count"] = value
        elif kind == "_sum":
            out[key]["sum"] = value
    return out


def diff(before, after):
    keys = set(before) | set(after)
    res = {}
    for k in keys:
        b = before.get(k, {"buckets": {}, "count": 0.0, "sum": 0.0})
        a = after.get(k, {"buckets": {}, "count": 0.0, "sum": 0.0})
        buckets = {le: a["buckets"].get(le, 0.0) - b["buckets"].get(le, 0.0)
                   for le in set(a["buckets"]) | set(b["buckets"])}
        d = {"buckets": buckets, "count": a["count"] - b["count"], "sum": a["sum"] - b["sum"]}
        if d["count"] > 0:
            res[k] = d
    return res


def quantile(buckets, count, q):
    """누적 버킷에서 q 분위. 버킷 경계 안은 선형 보간 — 경계 해상도가 곧 오차 하한이다."""
    if count <= 0 or not buckets:
        return None
    target = q * count
    prev_le, prev_c = 0.0, 0.0
    for le in sorted(buckets):
        c = buckets[le]
        if c >= target:
            if c == prev_c:
                return le
            frac = (target - prev_c) / (c - prev_c)
            return prev_le + (le - prev_le) * frac
        prev_le, prev_c = le, c
    return None


def main(root):
    root = Path(root)
    scrapes = root / "scrape"
    if not scrapes.is_dir():
        print(f"🔴 {scrapes} 없음"); return 1

    # 파일명 규약: b{블록}_{팔}_{before|after}.txt
    # 🔴 팔은 «파일명» 에서 읽는다. 3차 라운드의 팔 B(webclient-nginx)·C(webclient-direct)는
    #    protocol 태그가 둘 다 "webclient" 라 지표만으로는 구분이 안 된다 — 두 팔이 한 줄로
    #    합쳐지면 뺄셈 자체가 성립하지 않는다.
    blocks = defaultdict(dict)
    for f in scrapes.glob("*.txt"):
        m = re.match(r"b(\d+)_([\w.-]+)_(before|after)$", f.stem)
        if m:
            blocks[(int(m.group(1)), m.group(2))][m.group(3)] = f

    rows = defaultdict(list)   # (rpc, protocol) -> [(block, count, mean_ms, p50, p95, p99)]
    for (block, arm), pair in sorted(blocks.items()):
        if "before" not in pair or "after" not in pair:
            print(f"⚠️  블록 {block}/{arm} — before/after 짝이 안 맞아 건너뜀"); continue
        for (protocol, rpc, outcome), d in diff(parse(pair["before"]), parse(pair["after"])).items():
            if outcome != "success":
                # 실패는 델타에서 빼되 있었다는 사실은 남긴다 — 조용히 버리지 않는다.
                print(f"⚠️  블록 {block}/{arm} {rpc} outcome={outcome} {int(d['count'])}건 (집계 제외)")
                continue
            if protocol != "?" and protocol not in arm:
                # 파일명의 팔과 지표의 protocol 태그가 어긋나면 팔 전환이 샌 것이다.
                print(f"🔴 블록 {block} 팔 {arm} 인데 지표 protocol={protocol} — 팔 귀속 의심")
            rows[(rpc, arm)].append((
                block, int(d["count"]), d["sum"] / d["count"] * 1000,
                (quantile(d["buckets"], d["count"], 0.50) or 0) * 1000,
                (quantile(d["buckets"], d["count"], 0.95) or 0) * 1000,
                (quantile(d["buckets"], d["count"], 0.99) or 0) * 1000,
            ))

    def mean_by_block(rpc, arm):
        return {b: mean for b, _, mean, *_ in rows.get((rpc, arm), [])}

    for rpc in sorted({r for r, _ in rows}):
        print(f"\n## {rpc}")
        print(f"{'팔':<10} {'블록':>4} {'건수':>6} {'평균ms':>8} {'p50ms':>8} {'p95ms':>8} {'p99ms':>8}")
        span = {}
        for protocol in sorted({p for r, p in rows if r == rpc}):
            vals = sorted(rows[(rpc, protocol)])
            for b, n, mean, p50, p95, p99 in vals:
                print(f"{protocol:<10} {b:>4} {n:>6} {mean:>8.3f} {p50:>8.3f} {p95:>8.3f} {p99:>8.3f}")
            span[protocol] = (min(v[2] for v in vals), max(v[2] for v in vals), len(vals))

        # 🔴 블록이 1개면 «범위» 가 점이라 겹칠 수가 없다 — 그대로 두면 판정이 항상
        #    「효과 있음」으로 나온다. 반복이 없으면 판정 자체를 안 한다
        #    ([[feedback_measure_design_needs_repeats]]).
        if len(span) == 2 and min(v[2] for v in span.values()) < 2:
            n_blocks = min(v[2] for v in span.values())
            print("\n   🟡 팔당 블록이 %d개뿐이라 판정하지 않는다 — 범위가 점이면 «안 겹친다» 는 항상 참이다." % n_blocks)
        elif len(span) == 2:
            (a, (a_lo, a_hi, _)), (b, (b_lo, b_hi, _)) = sorted(span.items())
            overlap = not (a_hi < b_lo or b_hi < a_lo)
            print(f"\n   {a} 평균 범위 [{a_lo:.3f}, {a_hi:.3f}] ms · {b} [{b_lo:.3f}, {b_hi:.3f}] ms")
            if overlap:
                print("   🟡 두 팔의 블록 평균 범위가 겹친다 — 이 무대에서는 «판별 불가».")
                print("      (load-test-strategy.md §2-1: 겹치면 델타를 «효과» 라 부르지 않는다)")
            else:
                faster, slower = (a, b) if a_hi < b_lo else (b, a)
                print(f"   ✅ 범위가 안 겹친다 — 이 무대·이 판 수에서 {faster} 가 {slower} 보다 빠르다.")
                print("      절대 크기는 이 동거 무대의 값이지 배포 구성의 값이 아니다.")

        # ── 3차 라운드 뺄셈 — 고정비가 홉인지 클라이언트 쪽인지 (transport-cost-breakdown §2)
        arms = {a for r, a in rows if r == rpc}
        if {"grpc", "webclient-nginx", "webclient-direct"} <= arms:
            A, B, C = mean_by_block(rpc, "grpc"), mean_by_block(rpc, "webclient-nginx"), mean_by_block(rpc, "webclient-direct")
            common = sorted(set(A) & set(B) & set(C))
            if common:
                print(f"\n   분해 (블록별, ms) — 세 팔이 다 있는 블록 {len(common)}개")
                print(f"   {'블록':>4} {'B−C(홉)':>10} {'C−A(잔여)':>12} {'B−A(합계)':>12}")
                hops, rests = [], []
                for b in common:
                    hop, rest = B[b] - C[b], C[b] - A[b]
                    hops.append(hop); rests.append(rest)
                    print(f"   {b:>4} {hop:>10.3f} {rest:>12.3f} {B[b]-A[b]:>12.3f}")
                hop_lo, hop_hi = min(hops), max(hops)
                rest_lo, rest_hi = min(rests), max(rests)
                print(f"   범위: 홉 [{hop_lo:.3f}, {hop_hi:.3f}] · 잔여 [{rest_lo:.3f}, {rest_hi:.3f}]")
                # 🔴 «어느 쪽이 주범인가» 는 두 범위가 안 겹칠 때만 말한다. 그리고 블록이
                #    1개면 두 범위가 «점» 이라 겹칠 수가 없으므로 판정 자체를 안 한다
                #    (평균 비교 쪽에 이미 같은 가드가 있다).
                if len(common) < 2:
                    print("   🟡 세 팔이 다 있는 블록이 %d개뿐이라 분해를 판정하지 않는다." % len(common))
                elif hop_lo > rest_hi:
                    print("   ➡ 홉이 잔여보다 크고 범위가 안 겹친다 — 기제는 nginx 홉 쪽이다.")
                elif rest_lo > hop_hi:
                    print("   ➡ 잔여가 홉보다 크고 범위가 안 겹친다 — 기제는 클라이언트 쪽(브리지·커넥션)이다.")
                else:
                    print("   🟡 두 몫의 범위가 겹친다 — 이 무대에서는 주범을 못 가른다.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
