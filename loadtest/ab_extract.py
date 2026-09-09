"""ghz / k6 결과 JSON 한 장에서 raw.tsv 한 줄을 뽑는다.

measure_grpc_vs_webclient.sh 전용. 스크립트 안에 heredoc 으로 넣지 않고 파일로 뺀 이유는
같은 값을 «두 도구의 서로 다른 JSON 모양» 에서 뽑아야 해서다 — 한 자리에 나란히 두면
두 팔의 숫자가 같은 정의인지 눈으로 확인할 수 있다. 정의가 어긋나면 A/B 가 통째로 무의미해진다.

  ghz : details[] 에 요청별 latency(ns)가 다 들어 있어서 백분위를 여기서 직접 계산한다.
        (기존 measure_grpc_single_request_latency.sh 와 같은 방식 — 판 사이 비교가 되도록 맞췄다)
  k6  : --summary-export 가 이미 med/p(95)/p(99)/max 를 ms 로 계산해 준다.

🔴 정의가 완전히 같지는 않다. ghz 는 OK 응답만 모아 백분위를 내고, k6 의 t_call 은
   상태코드와 무관하게 전부 담는다. 그래서 fail 이 0 이 아닌 판은 두 팔을 나란히 놓으면 안 된다 —
   rig 가 버림 블록에서 표본 0 을 막고, 표에 fail 열을 같이 찍는 이유가 이것이다.
"""

import json
import sys


def _row(raw, block, conc, size, arm, count, ok, fail, p50, p95, p99, mx):
    with open(raw, "a", encoding="utf-8") as fh:
        fh.write(
            f"{block}\t{conc}\t{size}\t{arm}\t{count}\t{ok}\t{fail}\t{p50}\t{p95}\t{p99}\t{mx}\n"
        )


def _fail_row(raw, block, conc, size, arm):
    _row(raw, block, conc, size, arm, "FAIL", "-", "-", "-", "-", "-", "-")


def main() -> None:
    tool, path, block, conc, size, arm, raw = sys.argv[1:8]

    try:
        doc = json.load(open(path, encoding="utf-8"))
    except Exception:
        # 파일이 없거나 깨졌다 = 그 칸은 표본을 못 만들었다. 조용히 0 으로 두지 않고
        # FAIL 로 남긴다 — 빈 칸이 «측정했는데 0» 으로 읽히면 안 된다.
        _fail_row(raw, block, conc, size, arm)
        return

    if tool == "ghz":
        status = doc.get("statusCodeDistribution") or {}
        count = doc.get("count", 0)
        ok = status.get("OK", 0)
        lat = sorted(
            d["latency"] for d in (doc.get("details") or []) if d.get("status") == "OK"
        )

        def pct(p):
            if not lat:
                return ""
            return round(lat[min(len(lat) - 1, int(len(lat) * p / 100))] / 1e6, 3)

        mx = round(max(lat) / 1e6, 3) if lat else ""
        _row(raw, block, conc, size, arm, count, ok, count - ok, pct(50), pct(95), pct(99), mx)
        return

    metrics = doc.get("metrics", {})

    def v(name, stat, default=0):
        return metrics.get(name, {}).get(stat, default)

    count = int(v("iterations", "count", 0))
    bad = int(v("bad_status", "count", 0))

    def r(x):
        return round(float(x), 3) if x not in (None, "") else ""

    _row(
        raw, block, conc, size, arm, count, count - bad, bad,
        r(v("t_call", "med")), r(v("t_call", "p(95)")),
        r(v("t_call", "p(99)")), r(v("t_call", "max")),
    )


if __name__ == "__main__":
    main()
