"""Evaluate the fixed-condition squat demo set declared in one JSON manifest.

The runner reuses evaluate_squat.py so offline and batch acceptance use the same
MediaPipe detector and SquatCounter. Paths in the manifest are resolved from the
repository root.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import sys


def _nonnegative_int(value, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{field} must be a nonnegative integer")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, help="Write the aggregate JSON result")
    args = parser.parse_args()

    if not args.manifest.is_file():
        parser.error("manifest does not exist")
    try:
        # PowerShell 5 writes UTF-8 JSON with a BOM by default; accept both forms.
        manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        parser.error(f"could not read manifest: {exc}")
    sample_fps = manifest.get("sample_fps", 3.0)
    if isinstance(sample_fps, bool) or not isinstance(sample_fps, (int, float)) \
            or not math.isfinite(sample_fps) or sample_fps <= 0:
        parser.error("sample_fps must be positive")
    clips = manifest.get("clips")
    if not isinstance(clips, list) or not clips:
        parser.error("clips must be a non-empty list")

    repo_root = Path(__file__).resolve().parents[2]
    evaluator = Path(__file__).with_name("evaluate_squat.py")
    rows = []
    all_passed = True
    for index, clip in enumerate(clips, 1):
        if not isinstance(clip, dict):
            parser.error(f"clips[{index}] must be an object")
        try:
            name = str(clip["name"])
            video_rel = Path(clip["video"])
            expected_reps = _nonnegative_int(clip["expected_reps"], "expected_reps")
            expected_complete = _nonnegative_int(
                clip["expected_complete_events"], "expected_complete_events"
            )
            expected_partial = _nonnegative_int(
                clip["expected_partial_events"], "expected_partial_events"
            )
        except (KeyError, TypeError, ValueError) as exc:
            parser.error(f"invalid clips[{index}]: {exc}")
        video = video_rel if video_rel.is_absolute() else repo_root / video_rel
        mismatches = []
        result = None
        if not video.is_file():
            mismatches.append("video file is missing")
        else:
            env = os.environ.copy()
            env["PYTHONPATH"] = str(repo_root / "ai-server")
            env["PYTHONIOENCODING"] = "utf-8"
            env["DEBUG"] = "false"
            completed = subprocess.run(
                [sys.executable, str(evaluator), str(video),
                 "--sample-fps", str(sample_fps), "--expected", str(expected_reps)],
                cwd=repo_root, env=env, capture_output=True, text=True, encoding="utf-8",
            )
            try:
                result = json.loads(completed.stdout)
            except json.JSONDecodeError:
                mismatches.append(f"evaluator returned invalid JSON (exit={completed.returncode})")
            if result is not None:
                events = result.get("coaching_events") or []
                previous_rep = 0
                complete_events = 0
                partial_events = 0
                for event in events:
                    current_rep = int(event.get("rep_count") or 0)
                    if current_rep > previous_rep:
                        complete_events += 1
                        previous_rep = current_rep
                    else:
                        partial_events += 1
                if not result.get("evaluation_valid"):
                    mismatches.append("less than 70% of sampled frames were usable or standing calibration failed")
                if result.get("reps") != expected_reps:
                    mismatches.append(f"reps {result.get('reps')} != {expected_reps}")
                if complete_events != expected_complete:
                    mismatches.append(f"complete events {complete_events} != {expected_complete}")
                if partial_events != expected_partial:
                    mismatches.append(f"partial events {partial_events} != {expected_partial}")
            else:
                complete_events = partial_events = None
        passed = not mismatches
        all_passed = all_passed and passed
        rows.append({
            "name": name,
            "video": str(video_rel),
            "passed": passed,
            "expected_reps": expected_reps,
            "actual_reps": result.get("reps") if result else None,
            "expected_complete_events": expected_complete,
            "actual_complete_events": complete_events if result else None,
            "expected_partial_events": expected_partial,
            "actual_partial_events": partial_events if result else None,
            "mismatches": mismatches,
        })

    output = {"passed": all_passed, "sample_fps": sample_fps, "clips": rows}
    rendered = json.dumps(output, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if all_passed else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
