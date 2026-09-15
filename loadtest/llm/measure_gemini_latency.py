#!/usr/bin/env python3
"""Gemini generateContent 응답시간 실측 — 주간 리포트 LLM 문장(report-generation-llm.md §14-4 1단계).

왜 재나: 아웃박스 별도 발행기(§5-2 안 A)의 lock-timeout·timeout-seconds 를 «batch × 최대 응답시간 여유» 로
정하려면 응답시간 분포가 있어야 한다. 근거 없는 숫자를 박지 않는다.

설계(feedback_measure_design_needs_repeats):
- 모델 M개를 라운드마다 시작 순서를 돌려 호출 — «모델 차이» 와 «시간 순서» 를 분리
- 첫 라운드는 버림판(warm-up) — 집계에서 제외
- 페이로드는 WeeklySummaryService 가 실제로 내는 A층·B층 집계 형태 그대로(숫자만, 개인정보 없음)
- 출력은 JSON 스키마 강제 — {summary, cited_metrics}. 인용 숫자가 입력에 있는지도 같이 센다(§3 검증의 예행)

실행: GEMINI_API_KEY=... python loadtest/llm/measure_gemini_latency.py --rounds 11 --models gemini-2.5-flash-lite,gemini-3.5-flash-lite
결과: loadtest/results/gemini-latency-<날짜>/raw.jsonl + summary.md
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timezone
from pathlib import Path

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"

# WeeklySummaryResponseDto 형태 그대로 — thisWeek/lastWeek(WeeklyTotalsDto) + repCurve(RepCurvePointDto[]) + worstReps
PAYLOAD = {
    "periodStart": "2026-09-07", "periodEnd": "2026-09-14",
    "thisWeek": {"sessions": 4, "totalReps": 58, "repWeightedSyncRate": 71.4, "sessionWeightedSyncRate": 72.9, "activeDays": 3},
    "lastWeek": {"sessions": 3, "totalReps": 41, "repWeightedSyncRate": 74.8, "sessionWeightedSyncRate": 75.1, "activeDays": 3},
    "repCurve": [
        {"repNumber": 1, "avgSyncRate": 78.2, "sampleCount": 4}, {"repNumber": 2, "avgSyncRate": 77.5, "sampleCount": 4},
        {"repNumber": 3, "avgSyncRate": 75.9, "sampleCount": 4}, {"repNumber": 4, "avgSyncRate": 70.1, "sampleCount": 4},
        {"repNumber": 5, "avgSyncRate": 67.3, "sampleCount": 4}, {"repNumber": 6, "avgSyncRate": 66.0, "sampleCount": 3},
        {"repNumber": 7, "avgSyncRate": 64.8, "sampleCount": 3}, {"repNumber": 8, "avgSyncRate": 63.9, "sampleCount": 2},
    ],
    "worstReps": [{"repNumber": 7, "count": 2}, {"repNumber": 8, "count": 1}, {"repNumber": 5, "count": 1}],
    "templateSentences": ["이번 주 3일 운동했다.", "지난주보다 rep 수가 41→58 로 늘었다.",
                          "rep 가중 싱크로율은 74.8→71.4 로 내려갔다.", "rep 4 이후 싱크로율이 내려간다."],
}

SYSTEM = (
    "너는 스쿼트 운동 주간 리포트의 문장을 쓰는 보조자다. 반드시 한국어로만 쓴다. "
    "입력 JSON 의 숫자만 인용하고, 계산·추정·의학적 조언·없는 사실은 절대 쓰지 않는다. "
    "할 일 두 가지: (1) 지난주 대비 무엇이 달라졌는지 2문장 이내 (2) rep 곡선에서 보이는 반복 패턴 1문장. "
    "출력은 JSON 하나: summary(문장들을 이은 한 문단, 3문장 이내), cited_metrics(인용한 숫자를 이름→값 으로)."
)
SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "summary": {"type": "STRING"},
        "cited_metrics": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "name": {"type": "STRING"}, "value": {"type": "NUMBER"}}, "required": ["name", "value"]}},
    },
    "required": ["summary", "cited_metrics"],
}


def input_numbers(obj, acc=None):
    acc = set() if acc is None else acc
    if isinstance(obj, dict):
        for v in obj.values():
            input_numbers(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            input_numbers(v, acc)
    elif isinstance(obj, (int, float)) and not isinstance(obj, bool):
        acc.add(round(float(obj), 3))
    return acc


def korean_only(text):
    # 한글·ASCII·기본 문장부호·화살표만 허용 — 영어 단어(ASCII 문자)는 rep 같은 용어 때문에 막지 않는다.
    # 그리고 한글 음절이 최소 하나 — ASCII 만으로 된 영문 문장은 «한국어만» 이 아니다(WeeklyReportOutputValidator 와 같은 규칙)
    has_hangul = any(0xAC00 <= ord(ch) <= 0xD7A3 for ch in text)
    return has_hangul and all(ord(ch) < 0x3000 or 0xAC00 <= ord(ch) <= 0xD7A3 or ch in "→" for ch in text)


def call(model, key, timeout):
    body = {
        "systemInstruction": {"parts": [{"text": SYSTEM}]},
        "contents": [{"role": "user", "parts": [{"text": json.dumps(PAYLOAD, ensure_ascii=False)}]}],
        "generationConfig": {"responseMimeType": "application/json", "responseSchema": SCHEMA, "temperature": 0.2},
    }
    req = urllib.request.Request(ENDPOINT.format(model=model, key=key), data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            status = r.status
    except urllib.error.HTTPError as e:
        return {"status": e.code, "latency_s": round(time.perf_counter() - t0, 3), "error": e.read().decode()[:300]}
    except Exception as e:  # noqa: BLE001 — 타임아웃·연결 오류 전부 한 줄로 기록
        return {"status": -1, "latency_s": round(time.perf_counter() - t0, 3), "error": repr(e)[:300]}
    lat = round(time.perf_counter() - t0, 3)
    d = json.loads(raw)
    usage = d.get("usageMetadata", {})
    cands = d.get("candidates") or []
    parts = (cands[0].get("content") or {}).get("parts") if cands and cands[0] else None
    if not parts:
        # 프롬프트 차단 등 — promptFeedback 만 오고 candidates 가 없다. 측정을 멈추지 말고 실패 행으로 남긴다.
        return {"status": status, "latency_s": lat, "error": "no-candidates: " + json.dumps(d.get("promptFeedback") or
                (cands[0].get("finishReason") if cands and cands[0] else None), ensure_ascii=False)[:200]}
    text = parts[0].get("text", "")
    out = {"status": status, "latency_s": lat, "prompt_tokens": usage.get("promptTokenCount"),
           "output_tokens": usage.get("candidatesTokenCount"), "thinking_tokens": usage.get("thoughtsTokenCount"),
           "finish": d["candidates"][0].get("finishReason")}
    try:
        parsed = json.loads(text)
        allowed = input_numbers(PAYLOAD)
        cited = [round(float(m["value"]), 3) for m in parsed.get("cited_metrics", [])]
        out.update({"json_ok": True, "summary": parsed.get("summary"), "cited_n": len(cited),
                    "cited_unknown": [c for c in cited if c not in allowed],
                    "korean_only": korean_only(parsed.get("summary", ""))})
    except Exception as e:  # noqa: BLE001
        out.update({"json_ok": False, "raw_text": text[:300], "parse_error": repr(e)[:120]})
    return out


def pct(xs, p):
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    f = int(k)
    c = min(f + 1, len(xs) - 1)
    return round(xs[f] + (xs[c] - xs[f]) * (k - f), 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="gemini-2.5-flash-lite,gemini-3.5-flash-lite,gemini-3.5-flash")
    ap.add_argument("--rounds", type=int, default=11, help="첫 라운드는 버림판")
    ap.add_argument("--timeout", type=float, default=60)
    ap.add_argument("--gap", type=float, default=1.0, help="호출 사이 대기(초) — 무료 티어 분당 한도 보호")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    key = os.environ.get("GEMINI_API_KEY") or sys.exit("GEMINI_API_KEY 없음")
    models = a.models.split(",")
    out_dir = Path(a.out or f"loadtest/results/gemini-latency-{date.today().isoformat()}")
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    with (out_dir / "raw.jsonl").open("w", encoding="utf-8") as f:
        for r in range(a.rounds):
            order = models[r % len(models):] + models[:r % len(models)]  # 라운드마다 시작 모델을 돌린다
            for m in order:
                res = call(m, key, a.timeout)
                res.update({"round": r, "model": m, "warmup": r == 0, "ts": datetime.now(timezone.utc).isoformat()})
                rows.append(res)
                f.write(json.dumps(res, ensure_ascii=False) + "\n")
                f.flush()
                print(f"r{r:02d} {m:28s} {res['status']} {res['latency_s']:6.2f}s out={res.get('output_tokens')} "
                      f"think={res.get('thinking_tokens')} json={res.get('json_ok')} unk={res.get('cited_unknown')}", flush=True)
                time.sleep(a.gap)

    lines = [f"# Gemini 응답시간 실측 — {date.today().isoformat()}", "",
             f"페이로드: 주간 집계(A층 2주 + rep 곡선 8점 + worst 3) ≈ {len(json.dumps(PAYLOAD))} bytes. "
             f"라운드 {a.rounds}(첫 라운드 버림), 모델 시작 순서 회전, 호출 간격 {a.gap}s, temperature 0.2, JSON 스키마 강제.", "",
             "| 모델 | n | 성공 | p50 | p95 | max | 출력 tok 중앙 | thinking tok 중앙 | JSON ok | 인용 숫자 불일치 건 | 한국어만 |",
             "|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|"]
    for m in models:
        rs = [x for x in rows if x["model"] == m and not x["warmup"]]
        ok = [x for x in rs if x["status"] == 200]
        lat = [x["latency_s"] for x in ok]
        if not lat:
            lines.append(f"| {m} | {len(rs)} | 0 | - | - | - | - | - | - | - | - |")
            continue

        def med(k):
            vals = [x[k] for x in ok if x.get(k) is not None]
            return statistics.median(vals) if vals else "-"

        lines.append(f"| {m} | {len(rs)} | {len(ok)} | {pct(lat, .5)} | {pct(lat, .95)} | {max(lat)} | {med('output_tokens')} | "
                     f"{med('thinking_tokens')} | {sum(1 for x in ok if x.get('json_ok'))}/{len(ok)} | "
                     f"{sum(1 for x in ok if x.get('cited_unknown'))} | {sum(1 for x in ok if x.get('korean_only'))}/{len(ok)} |")
    errs = [x for x in rows if x["status"] != 200]
    lines += ["", f"오류 {len(errs)}건: " + ", ".join(f"r{x['round']} {x['model']} {x['status']}" for x in errs)]
    lines += ["", "## 샘플 출력 (모델별 마지막 성공 1건)", ""]
    for m in models:
        ok = [x for x in rows if x["model"] == m and x["status"] == 200 and x.get("json_ok")]
        if ok:
            lines += [f"- **{m}**: {ok[-1]['summary']}  \n  cited_unknown={ok[-1]['cited_unknown']}"]
    (out_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
