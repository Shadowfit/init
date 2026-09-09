"""ㄱ안 실측 — `static_image_mode=True` 의 프레임당 비용은 몇 배인가.

배경: `docs/decisions/session-detector-ownership.md:19` 가 ㄱ안(`static_image_mode=True`)을
**정성 논거만으로** 탈락시켰다("프레임당 비용이 오른다"). 같은 문서의 ㄹ안은 41~63%p 라는
숫자로 닫혔는데 ㄱ안만 숫자가 없다. 이 rig 가 그 숫자 하나(`k = B/A`)를 만든다.

무엇이 걸려 있나 — True 면 트래킹 상태가 **애초에 없다**. 그러면 #164 의 교차오염
(검출기를 세션 간에 돌려쓰면 20/20 → 11/20)이 구조적으로 불가능해지고, 검출기가
세션에 묶일 이유가 사라진다 → sticky routing · ReattachAnalysis · X-AI-Worker 가 함께 없어진다.

설계(왜 이 모양인가):
  - **팔 2개**: A=`static_image_mode=False`(현행) / B=`static_image_mode=True`(ㄱ안).
    다른 변수는 전부 고정 — 같은 frames.json, 같은 model_complexity, 같은 프로세스, 순차 실행.
  - **버림판 1회**: 첫 판은 버린다. 터보/캐시/mediapipe 지연할당이 첫 판에만 붙는다.
  - **판마다 팔 순서 반전**: 팔당 1판이면 「팔」과 「판 순서」가 분리되지 않는다.
    A,B / B,A / A,B ... 로 번갈아 돌려 순서 효과를 팔에서 뗀다.
  - **팔 실행마다 새 검출기 + 워밍업 1프레임(계측 제외)**: 이전 팔의 상태를 안 물려받는다.
  - **프레임당 시간을 전부 기록**한다. 평균만 보면 꼬리를 못 본다.

검산(출력에 상시 포함):
  - 전제: A 의 검출률이 100% 인가. 아니면 이 입력으로는 아무것도 못 잰다.
  - B 의 검출률 — True 는 매 프레임 독립 탐지라 100% 여야 한다. 아니면 입력 탓이다.
  - 판 간 산포: 인접 판의 차가 팔 간 차보다 크면 이 판은 k 를 못 만든다.

⚠️ 절대값 인용 금지 — 합성 프레임(frames.json meta 참조) + i3-6100 2물리코어.
   **옮길 수 있는 것은 비율 k 뿐이다.**
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import platform
import statistics
import subprocess
import sys
import time

DEFAULT_FRAMES = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "results", "coresidency-2026-08-15", "frames.json")


def load_images(frames_path: str):
    import cv2
    import numpy as np
    frames = json.load(open(frames_path, encoding="utf-8"))["frames"]
    imgs = []
    for b64 in frames:
        raw = base64.b64decode(b64.split(",", 1)[-1])
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
        imgs.append(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    return imgs


def cpu_load() -> float | None:
    """측정 중 배경 부하. 값이 아니라 «조건» 으로 남긴다."""
    try:
        out = subprocess.run(["wmic", "cpu", "get", "loadpercentage"],
                             capture_output=True, text=True, timeout=10).stdout
        for line in out.splitlines():
            s = line.strip()
            if s.isdigit():
                return float(s)
    except Exception:  # noqa: BLE001
        pass
    return None


def run_arm(static_mode: bool, imgs, n_frames: int, complexity: int,
            warmup: int = 5) -> dict:
    """팔 1회. 새 검출기 → 워밍업 warmup 프레임(제외) → n_frames 순차."""
    import mediapipe as mp
    pose = mp.solutions.pose.Pose(model_complexity=complexity,
                                  static_image_mode=static_mode)
    try:
        # 워밍업 — 계측 밖. 1장으로는 부족하다: True 는 매 호출이 전체 탐지 그래프라
        # 첫 몇 호출에 지연 할당이 붙어 n 이 작으면 그 전이가 평균을 통째로 먹는다
        # (스모크 n=5 에서 첫 판만 1686ms/프레임이 나왔다).
        for _ in range(warmup):
            pose.process(imgs[0])
        per_frame_ms: list[float] = []
        detected = 0
        for i in range(n_frames):
            img = imgs[i % len(imgs)]
            t0 = time.perf_counter()
            res = pose.process(img)
            per_frame_ms.append((time.perf_counter() - t0) * 1000.0)
            if res.pose_landmarks:
                detected += 1
    finally:
        pose.close()
    ordered = sorted(per_frame_ms)
    return {
        "static_image_mode": static_mode,
        "n": n_frames,
        "detected": detected,
        "detect_rate": round(detected / n_frames, 4),
        "mean_ms": round(statistics.fmean(per_frame_ms), 3),
        "p50_ms": round(ordered[len(ordered) // 2], 3),
        "p95_ms": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))], 3),
        "max_ms": round(ordered[-1], 3),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", default=DEFAULT_FRAMES)
    ap.add_argument("--rounds", type=int, default=5, help="버림판 제외 판 수")
    ap.add_argument("--n", type=int, default=150, help="팔 1회당 프레임 수")
    ap.add_argument("--complexity", type=int, default=1)
    ap.add_argument("--warmup", type=int, default=5, help="계측 제외 워밍업 프레임 수")
    ap.add_argument("--json", default="", help="이 경로에 결과 JSON 저장")
    a = ap.parse_args()

    import mediapipe as mp

    imgs = load_images(a.frames)
    meta = json.load(open(a.frames, encoding="utf-8")).get("meta", {})

    box = {
        "python": sys.version.split()[0],
        "mediapipe": mp.__version__,
        "platform": platform.platform(),
        "processor": platform.processor(),
        "cpu_count_logical": os.cpu_count(),
        "model_complexity": a.complexity,
        "frames_meta": meta,
        "n_per_arm": a.n,
        "rounds": a.rounds,
        "warmup_frames": a.warmup,
        "cpu_load_before": cpu_load(),
    }
    print(json.dumps({"box": box}, ensure_ascii=False))

    # ── 버림판 ──────────────────────────────────────────────
    print("[discard] 버림판 시작", flush=True)
    run_arm(False, imgs, a.n, a.complexity, a.warmup)
    run_arm(True, imgs, a.n, a.complexity, a.warmup)
    print("[discard] 버림판 완료 — 이 판은 버린다", flush=True)

    # ── 본 판: 판마다 팔 순서 반전 ─────────────────────────
    rows: list[dict] = []
    for r in range(a.rounds):
        order = [False, True] if r % 2 == 0 else [True, False]
        for static_mode in order:
            res = run_arm(static_mode, imgs, a.n, a.complexity, a.warmup)
            res["round"] = r
            res["slot"] = order.index(static_mode)     # 판 안에서 몇 번째로 돌았나
            res["arm"] = "B_static_true" if static_mode else "A_tracking_false"
            rows.append(res)
            print(json.dumps(res, ensure_ascii=False), flush=True)

    box["cpu_load_after"] = cpu_load()

    # ── 요약 + 검산 ────────────────────────────────────────
    def pick(arm: str) -> list[dict]:
        return [r for r in rows if r["arm"] == arm]

    A, B = pick("A_tracking_false"), pick("B_static_true")
    a_means = [r["mean_ms"] for r in A]
    b_means = [r["mean_ms"] for r in B]
    a_mean, b_mean = statistics.fmean(a_means), statistics.fmean(b_means)

    summary = {
        "A_tracking_false": {
            "mean_ms": round(a_mean, 3),
            "round_means": [round(x, 3) for x in a_means],
            "spread_ms": round(max(a_means) - min(a_means), 3),
            "detect_rate": round(statistics.fmean([r["detect_rate"] for r in A]), 4),
        },
        "B_static_true": {
            "mean_ms": round(b_mean, 3),
            "round_means": [round(x, 3) for x in b_means],
            "spread_ms": round(max(b_means) - min(b_means), 3),
            "detect_rate": round(statistics.fmean([r["detect_rate"] for r in B]), 4),
        },
        "k_B_over_A": round(b_mean / a_mean, 3),
        "k_range": [round(min(b_means) / max(a_means), 3),
                    round(max(b_means) / min(a_means), 3)],
    }

    # 검산 — 통과 못 하면 k 를 인용하면 안 된다
    arm_gap = abs(b_mean - a_mean)
    checks = {
        "전제: A 검출률 100%": summary["A_tracking_false"]["detect_rate"] == 1.0,
        "B 검출률 100% (True 는 매 프레임 독립 탐지)":
            summary["B_static_true"]["detect_rate"] == 1.0,
        "판 간 산포 < 팔 간 차 (팔 차가 판 노이즈보다 큰가)":
            max(summary["A_tracking_false"]["spread_ms"],
                summary["B_static_true"]["spread_ms"]) < arm_gap,
    }
    out = {"box": box, "rows": rows, "summary": summary, "checks": checks}
    print(json.dumps({"summary": summary, "checks": checks}, ensure_ascii=False, indent=1))

    if a.json:
        os.makedirs(os.path.dirname(os.path.abspath(a.json)), exist_ok=True)
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        print(f"[saved] {a.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
