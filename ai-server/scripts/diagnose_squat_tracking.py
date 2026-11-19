"""Diagnose pose-tracking and squat-counter stages in existing video clips.

Usage: python scripts/diagnose_squat_tracking.py clip1.mp4 clip2.mp4
The default 3 fps and visibility threshold match evaluate_squat.py.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict, replace
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2

from app.core.mediapipe_detector import PoseDetector
from app.core.squat_counter import SquatCounter, SquatThresholds, observe_squat


JOINTS = {
    11: "left_shoulder", 12: "right_shoulder",
    23: "left_hip", 24: "right_hip",
    25: "left_knee", 26: "right_knee",
    27: "left_ankle", 28: "right_ankle",
}


def diagnose(video_path: Path, sample_fps: float, visibility: float) -> dict:
    video = cv2.VideoCapture(str(video_path))
    source_fps = video.get(cv2.CAP_PROP_FPS)
    if not video.isOpened() or not math.isfinite(source_fps) or source_fps <= 0:
        video.release()
        raise ValueError(f"Could not read video FPS: {video_path}")
    detector = PoseDetector()
    counter = SquatCounter(replace(SquatThresholds(), visibility=visibility))
    counts: Counter[str] = Counter()
    low_joints: Counter[str] = Counter()
    stages: Counter[str] = Counter()
    transitions: list[dict] = []
    index = 0
    next_time = 0.0
    width = int(video.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(video.get(cv2.CAP_PROP_FRAME_HEIGHT))
    try:
        while True:
            ok, image = video.read()
            if not ok:
                break
            timestamp = index / source_fps
            index += 1
            if timestamp + 1e-9 < next_time:
                continue
            next_time = timestamp + 1 / sample_fps
            landmarks = detector.detect(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
            if landmarks is None:
                reason = "no_pose"
            else:
                by_index = {point.index: point for point in landmarks}
                missing = [name for number, name in JOINTS.items() if number not in by_index]
                low = [name for number, name in JOINTS.items()
                       if number in by_index and by_index[number].visibility < visibility]
                low_joints.update(low)
                try:
                    observe_squat(landmarks, width / height, visibility)
                except ValueError:
                    reason = "missing_joint" if missing else "low_visibility" if low else "invalid_geometry"
                else:
                    reason = "usable"
            before = counter.stage
            observation, _ = counter.update(landmarks, timestamp, width / height)
            counts[reason] += 1
            stages[counter.stage] += 1
            if before != counter.stage:
                transitions.append({"time_sec": round(timestamp, 3),
                                    "from": before, "to": counter.stage,
                                    "knee": round(observation.knee_angle, 1) if observation else None,
                                    "hip": round(observation.hip_angle, 1) if observation else None,
                                    "reps": counter.rep_count,
                                    "guidance": counter.guidance})
    finally:
        video.release()
        detector.close()
    sampled = sum(counts.values())
    usable = counts["usable"]
    calibrated = sampled - stages["waiting_for_standing"]
    return {
        "video": str(video_path), "width": width, "height": height,
        "source_fps": source_fps, "sample_fps": sample_fps,
        "visibility_threshold": visibility, "sampled_frames": sampled,
        "reasons": dict(counts), "low_visibility_joints": dict(low_joints),
        "valid_frame_ratio": round(usable / sampled, 3) if sampled else 0,
        "calibrated_frames": calibrated,
        "evaluation_valid": calibrated > 0 and usable / sampled >= 0.7 if sampled else False,
        "stage_frames": dict(stages), "stage_transitions": transitions,
        "final_stage": counter.stage, "reps": counter.rep_count,
        "thresholds": asdict(counter.thresholds),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("videos", type=Path, nargs="+")
    parser.add_argument("--sample-fps", type=float, default=3.0)
    parser.add_argument("--visibility", type=float, default=SquatThresholds.visibility)
    args = parser.parse_args()
    if not math.isfinite(args.sample_fps) or args.sample_fps <= 0:
        parser.error("sample-fps must be positive")
    if not 0 <= args.visibility <= 1:
        parser.error("visibility must be between 0 and 1")
    if any(not video.is_file() for video in args.videos):
        parser.error("Every video must exist")
    print(json.dumps([diagnose(video, args.sample_fps, args.visibility)
                      for video in args.videos], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
