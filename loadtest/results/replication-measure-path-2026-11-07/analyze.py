"""measure_path.sh 산출물 요약 — 팔·판별 지연 분포와 팔 X 의 분해.

사용: python analyze.py [_out 디렉터리]   (표준 라이브러리만)

앞 판의 하트비트가 남아 있는 표본(이번 판 하트비트가 아직 안 도착한 것)은 뺀다:
  X — 리플리카가 돌려준 beat 가 이번 판 첫 beat 보다 작으면
  Y·Z — 하트비트가 이번 판 읽기 시작보다 (WARM + 1)초 이상 전이면 (쓰기는 읽기보다 WARM 초 먼저 시작)
팔 X 는 «리플리카가 들고 있어야 할 beat» 를 쓰기 기록(w1 + 5초 ≤ 읽기 시점)으로 다시 계산해
실제 돌려받은 beat 와 맞춰 본다 — 맞으면 X 의 지연은 «5초 + 하트비트 간격의 일부 + 읽기 exec» 로
설명된다.
"""
import csv
import statistics as st
import sys
from datetime import datetime
from pathlib import Path

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).with_name("_out"))
WARM = 7.0
DELAY_US = 5_000_000


def rows(name):
    with open(OUT / name, encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def q(xs, p):
    xs = sorted(xs)
    if not xs:
        return float("nan")
    k = (len(xs) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def summ(xs):
    return dict(n=len(xs), min=min(xs), p50=q(xs, .5), p95=q(xs, .95), max=max(xs)) if xs else dict(n=0)


trials = {r["trial"]: r for r in rows("trials.tsv")}
out = []

# --- X
xs = rows("x_samples.tsv")
by_t = {}
for r in xs:
    by_t.setdefault(r["trial"], []).append(r)
x_detail = []
for t, rs in by_t.items():
    first_beat = int(rs[0]["beat_us"])
    writes = [(int(r["w1"]), int(r["beat_us"])) for r in rs]
    lags, periods, rexec, wexec, match = [], [], [], [], 0
    prev = None
    for r in rs:
        beat, rb = int(r["beat_us"]), int(r["rep_beat_us"])
        r0, r1 = int(r["r0"]), int(r["r1"])
        if prev is not None:
            periods.append((beat - prev) / 1e6)
        prev = beat
        rexec.append((r1 - r0) / 1e6)
        wexec.append((int(r["w1"]) - int(r["w0"])) / 1e6)
        if rb < first_beat:
            continue
        lag = (r1 - rb) / 1e6
        lags.append(lag)
        # 읽기 구간 중 언제 읽혔는지 모르므로 r0 기준·r1 기준 둘 다 허용
        exp_lo = max([b for w, b in writes if w + DELAY_US <= r0], default=None)
        exp_hi = max([b for w, b in writes if w + DELAY_US <= r1], default=None)
        ok = rb in (exp_lo, exp_hi) or (exp_lo is not None and exp_hi is not None and exp_lo <= rb <= exp_hi)
        match += ok
        x_detail.append((t, r["i"], f"{lag:.3f}", r["sbs"], ok))
    s = summ(lags)
    s.update(arm="X", trial=t, kind=trials[t]["kind"],
             beat_period_p50=q(periods, .5), read_exec_p50=q(rexec, .5), write_exec_p50=q(wexec, .5),
             model_match=f"{match}/{len(lags)}")
    out.append(s)

# --- Y
ys = rows("y_samples.tsv")
by_t = {}
for r in ys:
    by_t.setdefault(r["trial"], []).append(r)
for t, rs in by_t.items():
    start = int(rs[0]["now_us"])
    lags = [float(r["lag_s"]) for r in rs if int(r["beat_us"]) >= start - (WARM + 1) * 1e6]
    s = summ(lags)
    s.update(arm="Y", trial=t, kind=trials[t]["kind"], read_exec_p50=q([float(r["read_rtt_ms"]) / 1000 for r in rs], .5))
    out.append(s)

# --- Z
z_decomp = []
zs = rows("z_samples.tsv")
by_t = {}
for r in zs:
    by_t.setdefault(r["trial"], []).append(r)
ts = lambda v: datetime.strptime(v, "%Y-%m-%d %H:%M:%S.%f").timestamp()
for t, rs in by_t.items():
    start = ts(rs[0]["at"])
    lags = [float(r["lag_s"]) for r in rs if ts(r["beat"]) >= start - (WARM + 1)]
    s = summ(lags)
    s.update(arm="Z", trial=t, kind=trials[t]["kind"])
    out.append(s)
    # 서버 내부 분해 — 이 판에서 관측된 적용 트랜잭션별(중복 제거)
    seen, qs, c2a, ap, ceil_gap = set(), [], [], [], []
    for r in rs:
        qs.append(float(r["queue_s"]))
        if r["a_oc"] in seen or ts(r["a_oc"]) < start - (WARM + 1):
            continue
        seen.add(r["a_oc"])
        c2a.append(float(r["commit_to_apply_start_s"]))
        ap.append(float(r["apply_s"]))
        oc = ts(r["a_oc"])
        ceil_gap.append(ts(r["a_sa"]) - (int(oc) + (oc % 1 > 0)))   # 적용 시작 − ceil(소스 커밋 초)
    z_decomp.append((t, summ(qs), summ(c2a), summ(ap), summ(ceil_gap)))

# --- exec 왕복
rtt = [int(r["rtt_us"]) / 1e6 for r in rows("exec_rtt.tsv")]

cols = ["trial", "kind", "arm", "n", "min", "p50", "p95", "max",
        "beat_period_p50", "read_exec_p50", "write_exec_p50", "model_match"]
out.sort(key=lambda s: int(s["trial"]))
fmt = lambda v: f"{v:.3f}" if isinstance(v, float) else str(v)
lines = ["\t".join(cols)] + ["\t".join(fmt(s.get(c, "")) for c in cols) for s in out]
lines.append("")
lines.append(f"# docker exec 왕복(SELECT 1) n={len(rtt)} min={min(rtt):.3f} p50={q(rtt,.5):.3f} "
             f"p95={q(rtt,.95):.3f} max={max(rtt):.3f} s")
for arm in "XYZ":
    pooled = []
    for s in out:
        if s["arm"] == arm and s["kind"] == "본판" and s.get("n"):
            pooled.append(s["p50"])
    if pooled:
        lines.append(f"# 본판 {arm}: 판별 p50 = " + ", ".join(f"{v:.3f}" for v in pooled))
lines.append("")
lines.append("# 팔 Z 서버 내부 분해(초) — 수신=수신완료−소스커밋 · 대기=적용시작−소스커밋 · "
             "적용=적용끝−적용시작 · ceil=적용시작−ceil(소스커밋 초)")
for t, q_, c_, a_, g_ in z_decomp:
    f = lambda d: (f"n={d['n']} min={d['min']:.3f} p50={d['p50']:.3f} max={d['max']:.3f}" if d.get("n") else "n=0")
    lines.append(f"# 판 {t}: 수신 {f(q_)} | 대기 {f(c_)} | 적용 {f(a_)} | ceil {f(g_)}")
text = "\n".join(lines)
print(text)
(OUT / "summary.tsv").write_text(text + "\n", encoding="utf-8")
with open(OUT / "x_detail.tsv", "w", encoding="utf-8") as f:
    f.write("trial\ti\tlag_s\tsbs\tmodel_ok\n")
    for d in x_detail:
        f.write("\t".join(map(str, d)) + "\n")
