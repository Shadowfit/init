"""6차(Spring 클라이언트 쪽 호출당 CPU) 집계 — 백엔드 스레드 그룹별 CPU/재부착을 두 팔에서 뺄셈한다.

설계: docs/decisions/grpc-webclient-spring-client-cost-round.md §2(그룹)·§3(지표)·§6(판정).
입력(measure_ai_call_concurrency.sh THREADS=1 이 남기는 것):
  threads/b{블록}_{팔}_c{c}_{before|after}.txt — «tid<TAB>comm<TAB>sum_exec_runtime(ns)» 한 줄씩(/proc/1/task/*/schedstat)
  k6/·cells.tsv·scrape/                          — 4·5차와 같음(재부착 건수·cgroup·지연)

그룹(comm 은 15자 절단이라 접두로):
  tomcat   http-nio-8080-e   재부착 핸들러 + (A) 스텁 프레이밍·디코딩 + (C) .block() 대기
  reactor  reactor-http-ep   (C) 인코딩·I/O·디코딩
  grpc-elg grpc-default-wo   (A) 클라이언트 채널 netty I/O (+ 두 팔 공통: AI→Spring 콜백 서버 I/O)
  grpc-exec grpc-default-ex  AI→Spring 콜백 서버 실행기 — 공통, 판정 밖
  actuator http-nio-9090-e   스크레이프 — 배경
  jit      C1/C2 Compiler    배경
  gc       G1 *, VM Thread   배경
  sched    shadowfit-sched   아웃박스 발행기 등 — 공통
  other    나머지(짧은 스레드 포함)
클라이언트 경로 = tomcat + reactor + grpc-elg. 판정은 겹침 규칙(load-test-strategy.md §2-1)만.

tid 매칭: before·after 양쪽에 있는 tid 는 차분, after 에만 있으면 전체(칸 안에서 생긴 스레드), before 에만
있으면 «샌 양» 으로 세어 따로 적는다(그 CPU 는 못 본다).

사용: python loadtest/analyze_spring_client_cost.py <결과디렉터리>
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_ai_call_concurrency import load_cells, load_k6, span, overlap, fmt_span  # noqa: E402
from analyze_ai_call_latency_ab import parse, diff, quantile  # noqa: E402

CELL = re.compile(r"b(\d+)_([\w.-]+)_c(\d+)_(before|after)$")
GROUPS = (
    ("tomcat", ("http-nio-8080-e",)),
    ("reactor", ("reactor-http-ep", "reactor-http-ni")),
    ("grpc-elg", ("grpc-default-wo",)),
    ("grpc-exec", ("grpc-default-ex", "grpc-default-bo", "grpc-server-con")),
    ("actuator", ("http-nio-9090-e",)),
    ("jit", ("C1 CompilerThre", "C2 CompilerThre")),
    ("gc", ("G1 ", "VM Thread", "VM Periodic")),
    ("sched", ("shadowfit-sched",)),
)
CLIENT_PATH = ("tomcat", "reactor", "grpc-elg")
A, C = "grpc", "webclient-native"


def group_of(comm):
    for g, prefixes in GROUPS:
        if any(comm.startswith(p) for p in prefixes):
            return g
    return "other"


def load_snapshot(path):
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 3 or not parts[2].isdigit():
            continue
        rows[int(parts[0])] = (parts[1], int(parts[2]))
    return rows


def group_delta(before, after):
    """그룹별 CPU-초 차분 + 샌 스레드 수."""
    out = defaultdict(float)
    leaked = 0
    for tid, (comm, ns) in after.items():
        base = before.get(tid, (comm, 0))[1]
        out[group_of(comm)] += (ns - base) / 1e9
    for tid in before:
        if tid not in after:
            leaked += 1
    return out, leaked


def main(root):
    root = Path(root)
    tdir = root / "threads"
    if not tdir.is_dir():
        print(f"🔴 {tdir} 없음 — THREADS=1 로 돌린 결과가 아니다"); return 1
    cells = load_cells(root)

    pairs = defaultdict(dict)
    for f in tdir.glob("b*_*.txt"):
        m = CELL.match(f.stem)
        if m:
            pairs[(int(m.group(1)), m.group(2), int(m.group(3)))][m.group(4)] = f

    # (block, arm, c) -> {group: cpu-ms/call}
    per_call = {}
    leaks = {}
    calls_of = {}
    failures = []
    for key, pair in sorted(pairs.items()):
        b, arm, c = key
        if "before" not in pair or "after" not in pair:
            print(f"⚠️  칸 b{b}/{arm}/c{c} — 스냅샷 짝이 안 맞아 건너뜀"); continue
        k6 = load_k6(root, b, arm, c)
        n = (k6 or {}).get("reattach_ok")
        if not n:
            failures.append((b, arm, c, "reattach_ok 없음")); continue
        for k in ("start_fail", "start_409", "reattach_bad", "end_bad"):
            if k6.get(k):
                failures.append((b, arm, c, f"k6 {k}={k6[k]}"))
        cell = cells.get(key)
        if cell and cell["k6_rc"] not in ("0", ""):
            failures.append((b, arm, c, f"k6 rc={cell['k6_rc']}"))
        d, leaked = group_delta(load_snapshot(pair["before"]), load_snapshot(pair["after"]))
        per_call[key] = {g: v / n * 1000 for g, v in d.items()}
        per_call[key]["client"] = sum(per_call[key].get(g, 0.0) for g in CLIENT_PATH)
        per_call[key]["threads_total"] = sum(d.values()) / n * 1000
        if cell and cell["cpu_s"].get("backend") is not None:
            per_call[key]["cgroup"] = cell["cpu_s"]["backend"] / n * 1000
        leaks[key] = leaked
        calls_of[key] = n

    tainted = {(b, a, c) for b, a, c, _ in failures}
    arms = [a for a in (A, C) if any(k[1] == a for k in per_call)] or sorted({k[1] for k in per_call})
    levels = sorted({k[2] for k in per_call})
    blocks = sorted({k[0] for k in per_call})

    def judge(title, metric, lower_is_better=True):
        print(f"\n## {title} (cpu-ms/재부착)")
        print(f"{'c':>4} " + " ".join(f"{a:>28}" for a in arms))
        for c in levels:
            vals = {}
            cols = []
            for arm in arms:
                v = {b: per_call[(b, arm, c)].get(metric) for b in blocks
                     if (b, arm, c) in per_call and (b, arm, c) not in tainted and per_call[(b, arm, c)].get(metric) is not None}
                vals[arm] = v
                cols.append(f"{fmt_span(span(list(v.values()))):>20} n={len(v):<2}")
            print(f"{c:>4} " + " ".join(f"{x:>28}" for x in cols))
            if len(arms) == 2:
                lo, hi = arms
                common = sorted(set(vals[lo]) & set(vals[hi]))
                deltas = [vals[hi][b] - vals[lo][b] for b in common]
                sl, sh = span(list(vals[lo].values())), span(list(vals[hi].values()))
                if not sl or not sh:
                    verdict = "🟡 한 팔이 비어 판정 없음"
                elif min(len(vals[lo]), len(vals[hi])) < 2:
                    verdict = "🟡 블록 <2, 판정 없음"
                elif overlap(sl, sh):
                    verdict = f"🟡 겹침 — 판별 불가 (범위 폭 {lo} {sl[1]-sl[0]:.2f} · {hi} {sh[1]-sh[0]:.2f})"
                else:
                    better = lo if (sl[1] < sh[0]) == lower_is_better else hi
                    verdict = f"✅ 안 겹침 — {better} 우세"
                print(f"     {hi}−{lo} 델타 {fmt_span(span(deltas)):>20} (블록 {len(deltas)})  {verdict}")

    print("# Spring 클라이언트 쪽 호출당 CPU — 스레드 그룹 차분 (6차)")
    judge("★ 클라이언트 경로 = tomcat + reactor + grpc-elg — 주 판정(§6 규칙 1)", "client")
    for g, why in (("tomcat", "핸들러 + (A) 스텁 프레이밍/디코딩 + (C) block 대기"),
                   ("reactor", "(C) 인코딩·I/O·디코딩 — A 는 0 이어야 한다"),
                   ("grpc-elg", "(A) 클라이언트 채널 netty I/O + 두 팔 공통의 콜백 서버(AI→Spring) I/O — C 에 남는 양이 서버 몫")):
        judge(f"{g} — {why} (규칙 2: 어디서 나나)", g)
    print("\n# 배경 그룹 — 판정 밖, 팔 간에 다르면 적는다(규칙 3)")
    for g in ("grpc-exec", "actuator", "jit", "gc", "sched", "other"):
        judge(g, g)
    judge("스레드 합 (모든 그룹) — cgroup 과 대조", "threads_total")
    judge("cgroup 차분 (4·5차와 같은 지표)", "cgroup")

    print("\n# 샌 스레드(before 에만 있던 tid) — 그 CPU 는 못 본다")
    for key in sorted(per_call):
        if leaks.get(key):
            print(f"  b{key[0]}/{key[1]}/c{key[2]}: {leaks[key]}개")
    if not any(leaks.values()):
        print("  없음")

    # 지연 — 상한 확인
    scrapes = root / "scrape"
    lat = defaultdict(dict)
    if scrapes.is_dir():
        sp = defaultdict(dict)
        for f in scrapes.glob("*.txt"):
            m = CELL.match(f.stem)
            if m:
                sp[(int(m.group(1)), m.group(2), int(m.group(3)))][m.group(4)] = f
        for (b, arm, c), pair in sp.items():
            if "before" in pair and "after" in pair:
                for (protocol, rpc, outcome), d in diff(parse(pair["before"]), parse(pair["after"])).items():
                    if rpc == "reattach" and outcome == "success" and d["count"]:
                        lat[(arm, c)][b] = (quantile(d["buckets"], d["count"], 0.50) or 0) * 1000
        print("\n# Reattach p50 (ms) — 왕복 델타는 클라이언트 대가의 상한")
        for c in levels:
            parts = [f"{arm} {fmt_span(span(list(lat[(arm, c)].values())))}" for arm in arms]
            if len(arms) == 2:
                lo, hi = arms
                common = sorted(set(lat[(lo, c)]) & set(lat[(hi, c)]))
                parts.append(f"{hi}−{lo} {fmt_span(span([lat[(hi, c)][b] - lat[(lo, c)][b] for b in common]))}")
            print(f"  c={c}: " + " · ".join(parts))

    print("\n# 실패 (규칙 4 — 위 판정에서 제외)")
    if not failures:
        print("  없음")
    for b, arm, c, what in sorted(failures):
        print(f"  b{b}/{arm}/c{c}: {what}")
    print("\n(절대값엔 calib cpu 병기(㉠). 결론 문장은 설계 §6 의 셋 중 하나로만 — 여기서는 안 고른다.)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
