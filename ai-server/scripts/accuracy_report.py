#!/usr/bin/env python3
"""정확도 비교 — 사람이 붙인 라벨(정답)과 AI 의 실제 출력을 비교해 «대략 이 정도 맞는다» 를 숫자로 낸다.

영상 24~32개를 촬영해 사람이 라벨링하고(rep 수 · rep 별 좋음/나쁨), 같은 영상을 실제 경로로 돌려 AI 출력을 기록한 뒤 이 스크립트로
비교한다. 표준 라이브러리만 쓴다 — 설치 없이 어디서나 돈다.

입력 (둘 다 CSV, 헤더 필수, UTF-8):

  라벨(사람)  video_id,labeler,exercise,camera_angle,lighting,true_rep_count,rep_no,rep_label,bad_type,note
  결과(AI)    video_id,ai_total_reps,rep_no,ai_rep_label,ai_sync_rate,ai_feedback_type,measured_status

  - rep_label / ai_rep_label : good | bad  (rep 마다 한 행. 0회 영상은 rep 행이 없어도 된다 — 영상당 한 행만 두고 rep_no·rep_label 을 비운다)
  - labeler : 라벨러 식별자. **같은 영상을 2명 이상이 따로 라벨링**하면 라벨러 간 일치율(Cohen's kappa)을 낸다 — 그 일치율이 AI 일치율의
    상한이다(사람끼리도 안 맞는 영역은 정답이 아니라 기준이 모호한 자리다).
  - measured_status : ok 가 아니면(no_person · no_rep · not_fully_visible 등) «AI 가 측정 못 한 영상» 으로 따로 센다.

사용법:
  python scripts/accuracy_report.py --labels labels.csv --results results.csv [--out report.md] [--json report.json]

⚠️ 표본이 작다(24~32개). 그래서 비율에는 **Wilson 95% 구간**을 같이 낸다 — «정확도 87%» 가 아니라 «87%(구간 70~95%, n=30)» 로 읽을 것.
   이 숫자는 «이 영상들에서 이 정도» 이지 일반적 정확도가 아니다.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from statistics import mean

LABELS = {"good", "bad"}
LABEL_COLS = {"video_id", "labeler", "true_rep_count", "rep_no", "rep_label"}
RESULT_COLS = {"video_id", "ai_total_reps", "rep_no", "ai_rep_label"}


class InputError(Exception):
    """입력 파일 형식 오류 — 어느 파일 몇째 줄인지 사람이 읽을 수 있게 담는다."""


# ───────────────────────────── 읽기 ─────────────────────────────

def _read_rows(path, required):
    with open(path, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise InputError(f"{path}: 필수 열이 없다 — {sorted(missing)} (있는 열: {reader.fieldnames})")
        return [(n, {k: (v or "").strip() for k, v in row.items()}) for n, row in enumerate(reader, start=2)]


def _int(path, line, field, value, allow_empty=False):
    if value == "" and allow_empty:
        return None
    try:
        return int(value)
    except ValueError:
        raise InputError(f"{path}:{line}: {field} 가 정수가 아니다(={value!r})") from None


def _label(path, line, field, value):
    v = value.lower()
    if v not in LABELS:
        raise InputError(f"{path}:{line}: {field} 는 good 또는 bad 여야 한다(={value!r})")
    return v


def load_labels(path):
    """→ {video_id: {labeler: {'count': int, 'reps': {rep_no: 'good'|'bad'}, 'meta': {...}}}}"""
    out = defaultdict(dict)
    for line, row in _read_rows(path, LABEL_COLS):
        vid, who = row["video_id"], row["labeler"]
        if not vid or not who:
            raise InputError(f"{path}:{line}: video_id·labeler 가 비어 있다")
        count = _int(path, line, "true_rep_count", row["true_rep_count"])
        entry = out[vid].setdefault(who, {"count": count, "reps": {}, "meta": {}})
        if entry["count"] != count:
            raise InputError(f"{path}:{line}: {vid}/{who} 의 true_rep_count 가 행마다 다르다({entry['count']} vs {count})")
        for key in ("exercise", "camera_angle", "lighting"):
            if row.get(key):
                entry["meta"].setdefault(key, row[key])
        rep_no = _int(path, line, "rep_no", row["rep_no"], allow_empty=True)
        if rep_no is not None:
            if rep_no in entry["reps"]:
                raise InputError(f"{path}:{line}: {vid}/{who} 에 rep_no {rep_no} 가 두 번 있다")
            entry["reps"][rep_no] = _label(path, line, "rep_label", row["rep_label"])
    for vid, by_who in out.items():
        for who, e in by_who.items():
            if e["reps"] and len(e["reps"]) != e["count"]:
                raise InputError(f"{path}: {vid}/{who} 의 rep 행 {len(e['reps'])}개가 true_rep_count {e['count']} 와 다르다")
    return out


def load_results(path):
    """→ {video_id: {'count': int, 'reps': {rep_no: label}, 'status': str}}"""
    out = {}
    for line, row in _read_rows(path, RESULT_COLS):
        vid = row["video_id"]
        if not vid:
            raise InputError(f"{path}:{line}: video_id 가 비어 있다")
        count = _int(path, line, "ai_total_reps", row["ai_total_reps"])
        entry = out.setdefault(vid, {"count": count, "reps": {}, "status": (row.get("measured_status") or "ok").lower()})
        if entry["count"] != count:
            raise InputError(f"{path}:{line}: {vid} 의 ai_total_reps 가 행마다 다르다({entry['count']} vs {count})")
        rep_no = _int(path, line, "rep_no", row["rep_no"], allow_empty=True)
        if rep_no is not None:
            if rep_no in entry["reps"]:
                raise InputError(f"{path}:{line}: {vid} 에 rep_no {rep_no} 가 두 번 있다")
            entry["reps"][rep_no] = _label(path, line, "ai_rep_label", row["ai_rep_label"])
    return out


# ───────────────────────────── 통계 ─────────────────────────────

def wilson(k, n, z=1.96):
    """이항 비율의 Wilson 구간. n=0 이면 None."""
    if n == 0:
        return None
    p = k / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, center - half), min(1.0, center + half))


def cohen_kappa(pairs):
    """[(a, b), ...] 두 라벨러의 같은 rep 에 대한 라벨 → kappa. 정의되지 않으면 None."""
    n = len(pairs)
    if n == 0:
        return None
    po = sum(1 for a, b in pairs if a == b) / n
    pe = 0.0
    for c in LABELS:
        pe += (sum(1 for a, _ in pairs if a == c) / n) * (sum(1 for _, b in pairs if b == c) / n)
    if pe >= 1.0:
        return 1.0 if po == 1.0 else None
    return (po - pe) / (1 - pe)


def _ratio(k, n):
    ci = wilson(k, n)
    return {"k": k, "n": n, "value": (k / n if n else None),
            "ci": (None if ci is None else [round(ci[0], 4), round(ci[1], 4)])}


# ───────────────────────────── 계산 ─────────────────────────────

def analyze(labels, results):
    videos = sorted(labels)
    per_video, disputed_videos, missing_ai, ai_not_measured = [], [], [], []
    labeler_pairs, labeler_count_agree = [], []
    cm = {("good", "good"): 0, ("good", "bad"): 0, ("bad", "good"): 0, ("bad", "bad"): 0}  # (정답, AI)
    extra_ai_reps = missed_reps = disputed_reps = no_rep_labels = 0
    mismatches = []

    # 라벨러 간 일치 — 같은 영상을 2명 이상이 라벨링한 경우
    for vid in videos:
        whos = sorted(labels[vid])
        if len(whos) < 2:
            continue
        counts = {labels[vid][w]["count"] for w in whos}
        labeler_count_agree.append(len(counts) == 1)
        for i in range(len(whos)):
            for j in range(i + 1, len(whos)):
                a, b = labels[vid][whos[i]]["reps"], labels[vid][whos[j]]["reps"]
                for rep_no in sorted(set(a) & set(b)):
                    labeler_pairs.append((a[rep_no], b[rep_no]))

    for vid in videos:
        by_who = labels[vid]
        counts = {e["count"] for e in by_who.values()}
        meta = {}
        for e in by_who.values():
            for k, v in e["meta"].items():
                meta.setdefault(k, v)

        # 정답: 라벨러가 모두 같은 rep 수를 적은 영상만 쓴다. 갈리면 정답이 아니라 기준 문제라 따로 낸다.
        if len(counts) != 1:
            disputed_videos.append({"video_id": vid, "counts_by_labeler": {w: e["count"] for w, e in sorted(by_who.items())}})
            continue
        true_count = counts.pop()
        # rep 별 정답: 모든 라벨러가 같은 라벨을 준 rep 만. 갈린 rep 은 제외하고 센다.
        truth_reps, rep_nos = {}, set()
        for e in by_who.values():
            rep_nos |= set(e["reps"])
        for rep_no in sorted(rep_nos):
            given = {e["reps"][rep_no] for e in by_who.values() if rep_no in e["reps"]}
            if len(given) == 1:
                truth_reps[rep_no] = next(iter(given))
            else:
                disputed_reps += 1

        ai = results.get(vid)
        if ai is None:
            missing_ai.append(vid)
            continue
        measured = ai["status"] == "ok"
        err = ai["count"] - true_count
        row = {"video_id": vid, "true": true_count, "ai": ai["count"], "error": err, "status": ai["status"], **meta}
        per_video.append(row)
        if not measured:
            ai_not_measured.append({"video_id": vid, "status": ai["status"], "true": true_count})

        # AI 가 rep 별 좋음/나쁨을 주지 않은 영상(횟수만 적었다 — evaluate_squat.py 의 reps 만 옮긴 경우)은 rep 단위 비교에서 뺀다.
        # 안 빼면 정답 rep 전부가 «AI 가 못 셌다» 로 잡힌다. 단 AI 가 0회라고 한 영상은 rep 행이 없는 것이 정상이라 그대로 비교한다.
        if ai["count"] > 0 and not ai["reps"]:
            no_rep_labels += 1
            continue
        for rep_no in sorted(set(truth_reps) | set(ai["reps"])):
            t, a = truth_reps.get(rep_no), ai["reps"].get(rep_no)
            if t is not None and a is not None:
                cm[(t, a)] += 1
                if t != a:
                    mismatches.append({"video_id": vid, "rep_no": rep_no, "truth": t, "ai": a})
            elif a is not None and rep_no > true_count:
                extra_ai_reps += 1   # AI 가 정답에 없는 rep 을 셌다
            elif t is not None and a is None:
                missed_reps += 1     # 정답에 있는 rep 을 AI 가 못 셌다

    errs = [r["error"] for r in per_video]
    judged = sum(cm.values())
    correct = cm[("good", "good")] + cm[("bad", "bad")]
    bad_truth = cm[("bad", "good")] + cm[("bad", "bad")]
    bad_pred = cm[("good", "bad")] + cm[("bad", "bad")]

    def by(field):
        groups = defaultdict(list)
        for r in per_video:
            groups[r.get(field, "(미기재)")].append(r)
        out = {}
        for key, rows in sorted(groups.items()):
            es = [x["error"] for x in rows]
            out[key] = {"videos": len(rows), "mae": round(mean(abs(e) for e in es), 3),
                        "exact": _ratio(sum(1 for e in es if e == 0), len(es))}
        return out

    return {
        "videos": {"labeled": len(videos), "compared": len(per_video), "missing_ai_result": missing_ai,
                   "labelers_disagree_on_count": disputed_videos},
        "rep_count": None if not errs else {
            "true_total": sum(r["true"] for r in per_video), "ai_total": sum(r["ai"] for r in per_video),
            "mae": round(mean(abs(e) for e in errs), 3), "max_abs_error": max(abs(e) for e in errs),
            "bias": round(mean(errs), 3),   # 양수면 AI 가 더 센다(과다 계수), 음수면 덜 센다
            "exact_match": _ratio(sum(1 for e in errs if e == 0), len(errs)),
            "within_one": _ratio(sum(1 for e in errs if abs(e) <= 1), len(errs)),
        },
        "rep_judgement": {
            "aligned_reps": judged, "accuracy": _ratio(correct, judged),
            "confusion": {"truth_good_ai_good": cm[("good", "good")], "truth_good_ai_bad": cm[("good", "bad")],
                          "truth_bad_ai_good": cm[("bad", "good")], "truth_bad_ai_bad": cm[("bad", "bad")]},
            "bad_recall": _ratio(cm[("bad", "bad")], bad_truth),         # 나쁜 자세를 잡아내는가
            "bad_precision": _ratio(cm[("bad", "bad")], bad_pred),       # «나쁨» 이라 한 것이 진짜 나쁜가
            "extra_ai_reps": extra_ai_reps, "missed_reps": missed_reps, "disputed_reps_excluded": disputed_reps,
            "videos_without_ai_rep_labels": no_rep_labels,
        },
        "labeler_agreement": None if not labeler_pairs and not labeler_count_agree else {
            "videos_with_multiple_labelers": len(labeler_count_agree),
            "rep_count_agree": _ratio(sum(labeler_count_agree), len(labeler_count_agree)),
            "rep_label_agree": _ratio(sum(1 for a, b in labeler_pairs if a == b), len(labeler_pairs)),
            "kappa": None if cohen_kappa(labeler_pairs) is None else round(cohen_kappa(labeler_pairs), 3),
        },
        "ai_not_measured": ai_not_measured,
        "by_camera_angle": by("camera_angle"),
        "by_lighting": by("lighting"),
        "failures": {"count_errors": [r for r in per_video if r["error"] != 0], "judgement_mismatches": mismatches},
    }


# ───────────────────────────── 출력 ─────────────────────────────

def _pct(r):
    if r is None or r["n"] == 0:
        return "—"
    lo, hi = r["ci"]
    return f"{r['value'] * 100:.0f}% ({r['k']}/{r['n']}, 95% 구간 {lo * 100:.0f}~{hi * 100:.0f}%)"


def render_markdown(rep):
    v = rep["videos"]
    L = ["# 정확도 비교 결과", "",
         f"- 라벨된 영상 {v['labeled']}개 · 비교한 영상 {v['compared']}개",
         "- ⚠️ 표본이 작다 — 아래 비율은 «이 영상들에서 이 정도» 이고 구간이 넓다. 일반적 정확도가 아니다.", ""]
    if v["missing_ai_result"]:
        L.append(f"- **AI 결과가 없는 영상 {len(v['missing_ai_result'])}개**: {', '.join(v['missing_ai_result'])}")
    if v["labelers_disagree_on_count"]:
        L.append("- **라벨러끼리 rep 수가 갈려 제외한 영상**: " + ", ".join(
            f"{d['video_id']}({d['counts_by_labeler']})" for d in v["labelers_disagree_on_count"]))
    rc = rep["rep_count"]
    L += ["", "## 1. rep 수", ""]
    if rc is None:
        L.append("비교할 영상이 없다.")
    else:
        L += [f"- 정답 합계 {rc['true_total']} · AI 합계 {rc['ai_total']}",
              f"- 영상당 절대오차 평균(MAE) **{rc['mae']}** · 최대 {rc['max_abs_error']} · 편향 {rc['bias']:+} "
              f"({'AI 가 더 센다' if rc['bias'] > 0 else 'AI 가 덜 센다' if rc['bias'] < 0 else '치우침 없음'})",
              f"- 정확히 일치: {_pct(rc['exact_match'])}", f"- ±1 이내: {_pct(rc['within_one'])}"]
    j = rep["rep_judgement"]
    c = j["confusion"]
    L += ["", "## 2. rep 별 판정 (좋음/나쁨)", "",
          f"- 정답과 AI 가 모두 있는 rep {j['aligned_reps']}개 중 일치: {_pct(j['accuracy'])}",
          "", "| 정답 \\ AI | 좋음 | 나쁨 |", "|---|---|---|",
          f"| 좋음 | {c['truth_good_ai_good']} | {c['truth_good_ai_bad']} |",
          f"| 나쁨 | {c['truth_bad_ai_good']} | {c['truth_bad_ai_bad']} |", "",
          f"- **나쁜 자세를 잡아내는 비율(재현율)**: {_pct(j['bad_recall'])}",
          f"- «나쁨» 이라 한 것이 진짜 나쁜 비율(정밀도): {_pct(j['bad_precision'])}",
          f"- AI 가 정답에 없는 rep 을 센 것 {j['extra_ai_reps']}개 · 정답 rep 을 못 센 것 {j['missed_reps']}개 · "
          f"라벨러가 갈려 제외한 rep {j['disputed_reps_excluded']}개"]
    if j["videos_without_ai_rep_labels"]:
        L.append(f"- AI 가 rep 별 좋음/나쁨을 주지 않아 rep 판정 비교에서 뺀 영상 {j['videos_without_ai_rep_labels']}개 (rep 수 비교에는 들어간다)")
    la = rep["labeler_agreement"]
    L += ["", "## 3. 라벨러 간 일치 (AI 일치율의 상한)", ""]
    if la is None:
        L.append("같은 영상을 2명 이상이 라벨링한 것이 없다 — 라벨러 간 일치를 낼 수 없다.")
    else:
        L += [f"- 2명 이상이 라벨한 영상 {la['videos_with_multiple_labelers']}개",
              f"- rep 수 일치: {_pct(la['rep_count_agree'])}", f"- rep 별 라벨 일치: {_pct(la['rep_label_agree'])}",
              f"- Cohen's kappa: {la['kappa'] if la['kappa'] is not None else '정의되지 않음'}"]
    L += ["", "## 4. 조건별 (rep 수 MAE)", ""]
    for title, key in (("카메라 각도", "by_camera_angle"), ("조명", "by_lighting")):
        L += [f"**{title}**", "", "| 값 | 영상 수 | MAE | 정확히 일치 |", "|---|---|---|---|"]
        for k, d in rep[key].items():
            L.append(f"| {k} | {d['videos']} | {d['mae']} | {_pct(d['exact'])} |")
        L.append("")
    L += ["## 5. AI 가 측정하지 못한 영상 (measured_status ≠ ok)", ""]
    L += ([f"- {d['video_id']}: {d['status']} (정답 {d['true']}회)" for d in rep["ai_not_measured"]]
          or ["없다."])
    f = rep["failures"]
    L += ["", "## 6. 틀린 사례", "", "**rep 수가 다른 영상**", ""]
    L += ([f"- {r['video_id']}: 정답 {r['true']} · AI {r['ai']} (오차 {r['error']:+})"
           + "".join(f" · {k}={r[k]}" for k in ("camera_angle", "lighting") if k in r) for r in f["count_errors"]]
          or ["없다."])
    L += ["", "**판정이 다른 rep**", ""]
    L += ([f"- {m['video_id']} #{m['rep_no']}: 정답 {m['truth']} · AI {m['ai']}" for m in f["judgement_mismatches"]]
          or ["없다."])
    return "\n".join(L) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--labels", required=True, help="사람 라벨 CSV")
    ap.add_argument("--results", required=True, help="AI 결과 CSV")
    ap.add_argument("--out", help="마크다운 보고서 저장 경로(없으면 표준출력)")
    ap.add_argument("--json", dest="json_out", help="JSON 보고서 저장 경로")
    args = ap.parse_args(argv)
    try:
        report = analyze(load_labels(args.labels), load_results(args.results))
    except (InputError, OSError) as e:
        print(f"입력 오류: {e}", file=sys.stderr)
        return 2
    md = render_markdown(report)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(md)
    else:
        sys.stdout.write(md)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
