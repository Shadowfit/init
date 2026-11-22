"""`scripts/accuracy_report.py` — 정확도 비교 스크립트.

기대값은 전부 **손으로 계산한** 작은 합성 데이터에서 나온다(아래 주석에 계산 과정). 코드가 낸 값을 그대로 기대값으로 베끼지 않았다 —
그러면 계산이 틀려도 시험이 통과한다. 실제 영상·AI 출력이 아니라 «비교 계산이 맞는가» 만 본다.
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "accuracy_report.py"
_spec = importlib.util.spec_from_file_location("accuracy_report", _SCRIPT)
ar = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ar)

LABEL_HEADER = "video_id,labeler,exercise,camera_angle,lighting,true_rep_count,rep_no,rep_label,bad_type,note\n"
RESULT_HEADER = "video_id,ai_total_reps,rep_no,ai_rep_label,ai_sync_rate,ai_feedback_type,measured_status\n"


def _L(vid, who, angle, light, count, reps):
    """라벨 행들. reps = 'g' / 'b' 문자열(rep_no 는 1부터). 0회면 한 행(빈 rep)."""
    if not reps:
        return f"{vid},{who},squat,{angle},{light},{count},,,,\n"
    return "".join(f"{vid},{who},squat,{angle},{light},{count},{i},{'good' if c == 'g' else 'bad'},,\n"
                   for i, c in enumerate(reps, start=1))


def _R(vid, count, reps, status="ok"):
    if not reps:
        return f"{vid},{count},,,,,{status}\n"
    return "".join(f"{vid},{count},{i},{'good' if c == 'g' else 'bad'},70,,{status}\n"
                   for i, c in enumerate(reps, start=1))


@pytest.fixture
def data(tmp_path):
    labels = LABEL_HEADER
    # v1 front/bright 정답 3 (g g b) — 두 라벨러 일치
    labels += _L("v1", "A", "front", "bright", 3, "ggb") + _L("v1", "B", "front", "bright", 3, "ggb")
    # v2 side/dark 정답 2 — A 는 (g b), B 는 (g g) : rep2 에서 갈림
    labels += _L("v2", "A", "side", "dark", 2, "gb") + _L("v2", "B", "side", "dark", 2, "gg")
    # v3 front/bright 정답 0회 — 두 라벨러 일치
    labels += _L("v3", "A", "front", "bright", 0, "") + _L("v3", "B", "front", "bright", 0, "")
    # v4 side/bright — A 는 4회, B 는 5회 : rep 수가 갈려 영상째 제외. 앞 4개 라벨은 같다(라벨러 간 일치 계산에는 들어간다)
    labels += _L("v4", "A", "side", "bright", 4, "ggbb") + _L("v4", "B", "side", "bright", 5, "ggbbg")
    # v5 라벨만 있고 AI 결과가 없다
    labels += _L("v5", "A", "front", "bright", 1, "g")
    # v6 front/dark 정답 2 (g b) — 라벨러 1명
    labels += _L("v6", "A", "front", "dark", 2, "gb")

    results = RESULT_HEADER
    results += _R("v1", 3, "gbb")                       # rep2 : 정답 good / AI bad
    results += _R("v2", 3, "ggg")                       # 정답 2 인데 3 — rep3 은 정답에 없는 rep
    results += _R("v3", 0, "", status="no_person")      # 0회를 0회로 — 단 AI 는 «측정 못 함» 으로 분류
    results += _R("v4", 4, "ggbb")
    results += _R("v6", 1, "g")                         # 정답 2 인데 1 — rep2 를 못 셌다
    lab, res = tmp_path / "labels.csv", tmp_path / "results.csv"
    lab.write_text(labels, encoding="utf-8")
    res.write_text(results, encoding="utf-8")
    return lab, res


@pytest.fixture
def report(data):
    lab, res = data
    return ar.analyze(ar.load_labels(str(lab)), ar.load_results(str(res)))


def test_영상_분류(report):
    v = report["videos"]
    assert v["labeled"] == 6
    assert v["compared"] == 4                      # v1 v2 v3 v6 (v4 는 라벨러 불일치로 제외, v5 는 AI 결과 없음)
    assert v["missing_ai_result"] == ["v5"]
    assert [d["video_id"] for d in v["labelers_disagree_on_count"]] == ["v4"]


def test_rep_수_오차(report):
    rc = report["rep_count"]
    # 오차: v1 0 · v2 +1 · v3 0 · v6 -1 → 절대오차 평균 2/4 = 0.5, 편향 0, 최대 1
    assert rc["true_total"] == 3 + 2 + 0 + 2 == 7
    assert rc["ai_total"] == 3 + 3 + 0 + 1 == 7
    assert rc["mae"] == 0.5
    assert rc["bias"] == 0
    assert rc["max_abs_error"] == 1
    assert (rc["exact_match"]["k"], rc["exact_match"]["n"]) == (2, 4)
    assert (rc["within_one"]["k"], rc["within_one"]["n"]) == (4, 4)


def test_rep_판정_혼동행렬(report):
    j = report["rep_judgement"]
    # 정렬된 rep: v1 (g→g, g→b, b→b) · v2 rep1 (g→g) · v6 rep1 (g→g) = 5개.  v2 rep2 는 라벨러가 갈려 제외.
    assert j["confusion"] == {"truth_good_ai_good": 3, "truth_good_ai_bad": 1,
                              "truth_bad_ai_good": 0, "truth_bad_ai_bad": 1}
    assert j["aligned_reps"] == 5
    assert (j["accuracy"]["k"], j["accuracy"]["n"]) == (4, 5)
    assert (j["bad_recall"]["k"], j["bad_recall"]["n"]) == (1, 1)       # 정답 나쁨 1개 중 잡은 것 1
    assert (j["bad_precision"]["k"], j["bad_precision"]["n"]) == (1, 2)  # AI «나쁨» 2개 중 진짜 나쁨 1
    assert j["extra_ai_reps"] == 1            # v2 의 rep3
    assert j["missed_reps"] == 1              # v6 의 rep2
    assert j["disputed_reps_excluded"] == 1   # v2 의 rep2


def test_라벨러_간_일치와_kappa(report):
    la = report["labeler_agreement"]
    # 2명 이상이 라벨한 영상 v1 v2 v3 v4 — rep 수가 같은 것은 v4 만 빼고 3/4
    assert la["videos_with_multiple_labelers"] == 4
    assert (la["rep_count_agree"]["k"], la["rep_count_agree"]["n"]) == (3, 4)
    # 같은 rep 쌍: v1 3 · v2 2 · v4 4(B 의 5번째는 짝이 없다) = 9쌍, 일치 3+1+4 = 8
    assert (la["rep_label_agree"]["k"], la["rep_label_agree"]["n"]) == (8, 9)
    # po = 8/9. A: good 5 bad 4 · B: good 6 bad 3 → pe = (5·6 + 4·3)/81 = 42/81.  kappa = (8/9 - 42/81)/(1 - 42/81) = 30/39 ≈ 0.769
    assert la["kappa"] == pytest.approx(30 / 39, abs=1e-3)


def test_AI_측정_실패_영상(report):
    # v3 : 정답 0회, AI 도 0회지만 measured_status=no_person 이라 «측정 못 한 영상» 에 따로 올라간다
    assert report["ai_not_measured"] == [{"video_id": "v3", "status": "no_person", "true": 0}]


def test_조건별_분해(report):
    angle, light = report["by_camera_angle"], report["by_lighting"]
    # front: v1(0) v3(0) v6(-1) → MAE 1/3 · 정확히 일치 2/3.  side: v2(+1) → MAE 1
    assert angle["front"]["videos"] == 3 and angle["front"]["mae"] == pytest.approx(1 / 3, abs=1e-3)
    assert (angle["front"]["exact"]["k"], angle["front"]["exact"]["n"]) == (2, 3)
    assert angle["side"]["videos"] == 1 and angle["side"]["mae"] == 1.0
    # bright: v1 v3 → 0 · dark: v2(+1) v6(-1) → 1
    assert light["bright"]["mae"] == 0.0
    assert light["dark"]["mae"] == 1.0


def test_틀린_사례_목록(report):
    f = report["failures"]
    assert {r["video_id"] for r in f["count_errors"]} == {"v2", "v6"}
    assert f["judgement_mismatches"] == [{"video_id": "v1", "rep_no": 2, "truth": "good", "ai": "bad"}]


def test_AI_가_횟수만_주면_rep_판정_비교에서_빼고_정답_rep_을_못_셌다고_세지_않는다(tmp_path):
    lab = tmp_path / "l.csv"
    res = tmp_path / "r.csv"
    lab.write_text(LABEL_HEADER + _L("a", "A", "front", "bright", 3, "ggb") + _L("z", "A", "front", "bright", 2, "gb"),
                   encoding="utf-8")
    # a : AI 가 3회라고만 적고 rep 행이 없다(evaluate_squat.py 의 reps 만 옮긴 경우).  z : AI 가 0회 — rep 행이 없는 것이 정상
    res.write_text(RESULT_HEADER + "a,3,,,,,ok\n" + _R("z", 0, ""), encoding="utf-8")

    rep = ar.analyze(ar.load_labels(str(lab)), ar.load_results(str(res)))

    j = rep["rep_judgement"]
    assert j["videos_without_ai_rep_labels"] == 1          # a
    assert j["missed_reps"] == 2                            # z 의 정답 rep 2개만 «못 셌다» — a 의 3개는 세지 않는다
    assert j["aligned_reps"] == 0
    # rep 수 비교에는 둘 다 들어간다: a 는 3-3=0, z 는 0-2=-2
    assert rep["rep_count"]["mae"] == 1.0 and rep["rep_count"]["bias"] == -1.0
    assert "rep 판정 비교에서 뺀 영상 1개" in ar.render_markdown(rep)


# ───────────── 통계 보조 함수 ─────────────

def test_wilson_구간():
    lo, hi = ar.wilson(8, 10)
    assert lo == pytest.approx(0.4902, abs=1e-3) and hi == pytest.approx(0.9433, abs=1e-3)
    assert ar.wilson(0, 0) is None
    lo, hi = ar.wilson(10, 10)
    assert hi == 1.0 and lo == pytest.approx(0.7225, abs=1e-3)


def test_kappa_경계():
    assert ar.cohen_kappa([]) is None
    assert ar.cohen_kappa([("good", "good")] * 3) == 1.0            # 한 라벨만 있고 전부 일치 → 정의상 1
    # A 는 전부 good, B 는 good 2·bad 1 → po = 2/3, pe = 2/3 → kappa 0 (우연 수준)
    assert ar.cohen_kappa([("good", "good"), ("good", "good"), ("good", "bad")]) == pytest.approx(0.0, abs=1e-9)
    # 완전 불일치(서로 반대) → kappa -1
    assert ar.cohen_kappa([("good", "bad"), ("bad", "good")]) == pytest.approx(-1.0)


# ───────────── 입력 검증 ─────────────

def _write(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text, encoding="utf-8")
    return str(p)


def test_필수_열이_없으면_오류(tmp_path):
    p = _write(tmp_path, "l.csv", "video_id,labeler\nv1,A\n")
    with pytest.raises(ar.InputError, match="필수 열"):
        ar.load_labels(p)


def test_라벨이_good_bad_가_아니면_줄_번호와_함께_오류(tmp_path):
    p = _write(tmp_path, "l.csv", LABEL_HEADER + "v1,A,squat,front,bright,1,1,maybe,,\n")
    with pytest.raises(ar.InputError, match=r"l\.csv:2.*good 또는 bad"):
        ar.load_labels(p)


def test_rep_행_수가_true_rep_count_와_다르면_오류(tmp_path):
    p = _write(tmp_path, "l.csv", LABEL_HEADER + _L("v1", "A", "front", "bright", 3, "gg"))
    with pytest.raises(ar.InputError, match="true_rep_count"):
        ar.load_labels(p)


def test_같은_rep_번호가_두_번이면_오류(tmp_path):
    p = _write(tmp_path, "r.csv", RESULT_HEADER + "v1,2,1,good,70,,ok\nv1,2,1,bad,70,,ok\n")
    with pytest.raises(ar.InputError, match="두 번"):
        ar.load_results(p)


def test_영상_안에서_총_횟수가_행마다_다르면_오류(tmp_path):
    p = _write(tmp_path, "r.csv", RESULT_HEADER + "v1,2,1,good,70,,ok\nv1,3,2,good,70,,ok\n")
    with pytest.raises(ar.InputError, match="행마다 다르다"):
        ar.load_results(p)


# ───────────── CLI ─────────────

def test_CLI_가_마크다운과_JSON_을_쓴다(data, tmp_path):
    lab, res = data
    out_md, out_json = tmp_path / "out.md", tmp_path / "out.json"
    p = subprocess.run([sys.executable, str(_SCRIPT), "--labels", str(lab), "--results", str(res),
                        "--out", str(out_md), "--json", str(out_json)], capture_output=True, text=True)
    assert p.returncode == 0, p.stderr
    md = out_md.read_text(encoding="utf-8")
    for needle in ("정확도 비교 결과", "나쁜 자세를 잡아내는 비율", "라벨러 간 일치", "표본이 작다", "v5", "v4"):
        assert needle in md, needle
    data_json = json.loads(out_json.read_text(encoding="utf-8"))
    assert data_json["rep_count"]["mae"] == 0.5


def test_CLI_입력_오류는_종료코드_2(tmp_path):
    bad = _write(tmp_path, "l.csv", "video_id\nv1\n")
    ok = _write(tmp_path, "r.csv", RESULT_HEADER)
    p = subprocess.run([sys.executable, str(_SCRIPT), "--labels", bad, "--results", ok], capture_output=True, text=True)
    assert p.returncode == 2
    assert "입력 오류" in p.stderr


def test_빈_입력도_죽지_않는다(tmp_path):
    lab = _write(tmp_path, "l.csv", LABEL_HEADER)
    res = _write(tmp_path, "r.csv", RESULT_HEADER)
    rep = ar.analyze(ar.load_labels(lab), ar.load_results(res))
    assert rep["rep_count"] is None
    assert "비교할 영상이 없다" in ar.render_markdown(rep)


def test_예시_CSV_가_실제로_돌아간다():
    """문서가 가리키는 예시 파일이 형식 변경으로 조용히 깨지지 않게 — 예시가 곧 사용자의 출발점이다."""
    samples = _SCRIPT.parent / "accuracy_samples"
    rep = ar.analyze(ar.load_labels(str(samples / "labels.example.csv")),
                     ar.load_results(str(samples / "results.example.csv")))
    assert rep["videos"]["compared"] == 3
    assert rep["rep_count"]["true_total"] == 5 and rep["rep_count"]["ai_total"] == 6
    assert rep["labeler_agreement"]["kappa"] == pytest.approx(0.545, abs=1e-3)
