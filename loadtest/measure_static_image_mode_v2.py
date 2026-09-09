"""ㄱ안 실측 v2 — 팔을 잘게 교차해 배경 드리프트를 상쇄한다.

v1(`measure_static_image_mode.py`)이 왜 부족했나:
  팔 하나를 150프레임 통째로 돌린 뒤 다음 팔로 넘어갔다. 한 팔이 도는 ~10초 동안 배경
  부하가 변하면 그 변화가 통째로 그 팔에 얹힌다. 실제로 B 의 어떤 판에서 **프레임 하나가
  7,106ms** 를 먹었고(배경 프로세스), 평균이 그걸 그대로 물었다. 판 간 산포가 팔 간 차보다
  커져서 검산이 떨어졌다 — k 를 못 만드는 상태.

v2 가 바꾼 것:
  1) **잘게 교차** — 팔당 150프레임을 10프레임 블록 15개로 쪼개 A,B,B,A,A,B... 로 번갈아
     돈다. 두 팔이 겪는 배경 조건이 ~1초 단위로 같아져서 공통 드리프트가 상쇄된다.
  2) **검출기를 계속 살려둔다** — 블록 사이에 A 의 트래킹이 끊기면 안 된다. A 는 프레임
     순서를 이어서 처리하므로(9→10) 교차해도 트래킹은 연속이다. 이게 A 에게 공정한 조건이다.
  3) **블록쌍 안에서 순서 반전** — 쌍마다 (A,B)/(B,A) 를 번갈아 「먼저 도는 쪽」 효과를 뗀다.
  4) **중앙값 통계** — 평균은 7초짜리 이상치 하나에 먹힌다. 블록별 p50 → 블록쌍 k → k 의
     중앙값·사분위. 임의 컷오프로 이상치를 «버리지» 않고 애초에 안 물리는 통계를 쓴다.
  5) 🔴 **음성대조군(`--control`)** — 두 팔 다 `static_image_mode=False` 로 돌린다.
     그때 k 가 1.0 근처에 좁게 모이지 않으면, **이 박스는 이 크기의 차이를 분해하지 못한다**는
     뜻이고 본 측정의 k 도 인용하면 안 된다. 이게 이 rig 의 진짜 검산이다.

⚠️ 절대값 인용 금지 — 합성 프레임 + 2물리코어. 옮길 수 있는 것은 비율 k 뿐이다.
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
    out = []
    for b64 in frames:
        raw = base64.b64decode(b64.split(",", 1)[-1])
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
        out.append(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    return out


def cpu_load() -> float | None:
    try:
        out = subprocess.run(["wmic", "cpu", "get", "loadpercentage"],
                             capture_output=True, text=True, timeout=10).stdout
        for line in out.splitlines():
            if line.strip().isdigit():
                return float(line.strip())
    except Exception:  # noqa: BLE001
        pass
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", default=DEFAULT_FRAMES)
    ap.add_argument("--blocks", type=int, default=20, help="블록쌍 수")
    ap.add_argument("--block", type=int, default=10, help="블록당 프레임 수")
    ap.add_argument("--complexity", type=int, default=1)
    ap.add_argument("--warmup", type=int, default=8)
    ap.add_argument("--control", action="store_true",
                    help="음성대조군 — 두 팔 다 static_image_mode=False")
    ap.add_argument("--json", default="")
    a = ap.parse_args()

    import mediapipe as mp

    imgs = load_images(a.frames)
    meta = json.load(open(a.frames, encoding="utf-8")).get("meta", {})

    # 팔 B 의 정체. control 이면 A 와 같은 값 → k 는 1.0 이 나와야 한다.
    b_static = False if a.control else True

    box = {
        "mode": "control(A vs A)" if a.control else "main(A vs B)",
        "python": sys.version.split()[0], "mediapipe": mp.__version__,
        "platform": platform.platform(), "cpu_count_logical": os.cpu_count(),
        "model_complexity": a.complexity, "frames_meta": meta,
        "blocks": a.blocks, "block_frames": a.block, "warmup": a.warmup,
        "arm_A_static_image_mode": False, "arm_B_static_image_mode": b_static,
        "cpu_load_before": cpu_load(),
    }
    print(json.dumps({"box": box}, ensure_ascii=False), flush=True)

    pose_a = mp.solutions.pose.Pose(model_complexity=a.complexity, static_image_mode=False)
    pose_b = mp.solutions.pose.Pose(model_complexity=a.complexity, static_image_mode=b_static)
    try:
        for _ in range(a.warmup):          # 워밍업 — 계측 밖
            pose_a.process(imgs[0])
            pose_b.process(imgs[0])

        # 각 팔이 프레임 순서를 «이어서» 처리한다 — A 의 트래킹을 끊지 않기 위해.
        cursor = {"A": 0, "B": 0}
        detected = {"A": 0, "B": 0}
        seen = {"A": 0, "B": 0}
        rows = []

        for bi in range(a.blocks):
            order = ["A", "B"] if bi % 2 == 0 else ["B", "A"]
            block_p50 = {}
            for slot, arm in enumerate(order):
                pose = pose_a if arm == "A" else pose_b
                times = []
                for _ in range(a.block):
                    img = imgs[cursor[arm] % len(imgs)]
                    cursor[arm] += 1
                    t0 = time.perf_counter()
                    res = pose.process(img)
                    times.append((time.perf_counter() - t0) * 1000.0)
                    seen[arm] += 1
                    if res.pose_landmarks:
                        detected[arm] += 1
                ordered = sorted(times)
                block_p50[arm] = ordered[len(ordered) // 2]
                rows.append({"block": bi, "arm": arm, "slot": slot,
                             "p50_ms": round(block_p50[arm], 3),
                             "mean_ms": round(statistics.fmean(times), 3),
                             "max_ms": round(ordered[-1], 3)})
            k = block_p50["B"] / block_p50["A"]
            print(f"block {bi:>2} A_first={order[0]=='A'} "
                  f"A_p50={block_p50['A']:7.2f}  B_p50={block_p50['B']:7.2f}  k={k:.3f}",
                  flush=True)
    finally:
        pose_a.close()
        pose_b.close()

    box["cpu_load_after"] = cpu_load()

    ks = []
    for bi in range(a.blocks):
        pa = [r for r in rows if r["block"] == bi and r["arm"] == "A"][0]["p50_ms"]
        pb = [r for r in rows if r["block"] == bi and r["arm"] == "B"][0]["p50_ms"]
        ks.append(pb / pa)
    ks_sorted = sorted(ks)
    q1 = ks_sorted[len(ks_sorted) // 4]
    q3 = ks_sorted[(len(ks_sorted) * 3) // 4]

    summary = {
        "k_median": round(statistics.median(ks), 3),
        "k_q1": round(q1, 3), "k_q3": round(q3, 3),
        "k_min": round(min(ks), 3), "k_max": round(max(ks), 3),
        "k_iqr": round(q3 - q1, 3),
        "A_p50_median_ms": round(statistics.median(
            [r["p50_ms"] for r in rows if r["arm"] == "A"]), 3),
        "B_p50_median_ms": round(statistics.median(
            [r["p50_ms"] for r in rows if r["arm"] == "B"]), 3),
        "A_detect_rate": round(detected["A"] / seen["A"], 4),
        "B_detect_rate": round(detected["B"] / seen["B"], 4),
        "worst_frame_ms": round(max(r["max_ms"] for r in rows), 1),
    }
    out = {"box": box, "rows": rows, "k_per_block": [round(x, 3) for x in ks],
           "summary": summary}
    print(json.dumps({"summary": summary}, ensure_ascii=False, indent=1))
    if a.json:
        os.makedirs(os.path.dirname(os.path.abspath(a.json)), exist_ok=True)
        json.dump(out, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"[saved] {a.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
