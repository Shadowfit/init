"""Replay a labeled side-view squat clip through the streaming repetition counter.

python scripts/evaluate_squat.py clip.mp4 --expected 5 --trace squat-trace.csv
"""
import argparse
import csv
from dataclasses import asdict
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2

from app.core.mediapipe_detector import PoseDetector
from app.core.squat_counter import DEPTH_MODE_THRESHOLDS, SquatCounter


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--sample-fps", type=float, default=3.0)
    parser.add_argument("--expected", type=int, help="Human-counted repetitions")
    parser.add_argument("--trace", type=Path, help="Per-sampled-frame CSV")
    parser.add_argument("--depth-mode", choices=["FULL", "HALF"], default="FULL",
                        help="Squat depth mode thresholds (2026-09-21). HALF's bottom_hip/"
                             "min_hip_drop are unvalidated against real footage -- see "
                             "docs/decisions/squat-repetition-counting.md")
    args = parser.parse_args()
    if not args.video.is_file():
        parser.error("Video file does not exist")
    if not math.isfinite(args.sample_fps) or args.sample_fps <= 0:
        parser.error("sample-fps must be positive")
    if args.expected is not None and args.expected < 0:
        parser.error("expected count must be nonnegative")

    video = cv2.VideoCapture(str(args.video))
    fps = video.get(cv2.CAP_PROP_FPS)
    if not video.isOpened() or not math.isfinite(fps) or fps <= 0:
        video.release()
        parser.error("Could not read video FPS")
    detector = PoseDetector()
    counter = SquatCounter(DEPTH_MODE_THRESHOLDS[args.depth_mode])
    trace = args.trace.open("w", newline="", encoding="utf-8") if args.trace else None
    writer = csv.writer(trace) if trace else None
    if writer:
        writer.writerow(["time_sec", "stage", "phase", "left_knee", "right_knee",
                         "left_hip", "right_hip", "reps", "guidance", "coaching_event"])
    index = sampled = usable = calibrated = 0
    coaching_events = []
    next_time = 0.0
    try:
        while True:
            ok, image = video.read()
            if not ok:
                break
            timestamp = index / fps
            index += 1
            if timestamp + 1e-9 < next_time:
                continue
            next_time = timestamp + 1 / args.sample_fps
            landmarks = detector.detect(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
            observation, _ = counter.update(
                landmarks, timestamp, image.shape[1] / image.shape[0]
            )
            sampled += 1
            usable += observation is not None
            calibrated += counter.stage != "waiting_for_standing"
            if counter.coaching_event:
                coaching_events.append({"time_sec": round(timestamp, 3),
                                        "message": counter.coaching_event,
                                        "rep_count": counter.rep_count})
            if writer:
                side_angles = ([round(observation.knees[side], 2)
                                if observation.visible_sides[side] else None
                                for side in (0, 1)] +
                               [round(observation.hips[side], 2)
                                if observation.visible_sides[side] else None
                                for side in (0, 1)]) if observation else [None] * 4
                writer.writerow([round(timestamp, 3), counter.stage, counter.phase,
                                 *side_angles,
                                 counter.rep_count, counter.guidance,
                                 counter.coaching_event or ""])
    finally:
        video.release()
        detector.close()
        if trace:
            trace.close()
    valid_ratio = usable / sampled if sampled else 0.0
    evaluation_valid = calibrated > 0 and valid_ratio >= 0.7
    result = {"video": str(args.video), "source_fps": fps,
              "sample_fps": args.sample_fps, "sampled_frames": sampled,
              "usable_frames": usable, "calibrated_frames": calibrated,
              "valid_frame_ratio": round(valid_ratio, 3),
              "evaluation_valid": evaluation_valid, "reps": counter.rep_count,
              "coaching_events": coaching_events,
              "thresholds": asdict(counter.thresholds)}
    if args.expected is not None:
        result["expected"] = args.expected
        result["absolute_error"] = abs(counter.rep_count - args.expected) if evaluation_valid else None
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if evaluation_valid else 2


if __name__ == "__main__":
    sys.exit(main())
