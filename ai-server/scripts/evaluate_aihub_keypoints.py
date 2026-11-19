"""Replay AI Hub CrossFit 2D keypoints through the squat counter.

This checks the counter only: AI Hub keypoints have no MediaPipe visibility score,
and a labeled action interval is not necessarily a verified rep-count label.

python scripts/evaluate_aihub_keypoints.py TL.zip sample_annotation.json
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import io
import json
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.squat_counter import SquatCounter
from app.models.pose import Landmark


JOINTS = (
    ("LShoulder", 11), ("RShoulder", 12),
    ("LHip", 23), ("RHip", 24),
    ("LKnee", 25), ("RKnee", 26),
    ("LAnkle", 27), ("RAnkle", 28),
)
WIDTH, HEIGHT, SOURCE_FPS = 1920, 1080, 30


def replay(handle) -> tuple[int, int, int, dict[str, int]]:
    counter = SquatCounter()
    stage_changes: Counter[str] = Counter()
    sampled = usable = 0
    rows = csv.DictReader(io.TextIOWrapper(handle, encoding="utf-8-sig", newline=""))
    for row in rows:
        frame = int(Path(row["image_filename"]).stem)
        if frame % 10 != 0:  # AI Hub source is 30 fps; ShadowFit evaluates 3 fps.
            continue
        try:
            points = [Landmark(index=index, x=float(row[name + "_x"]) / WIDTH,
                               y=float(row[name + "_y"]) / HEIGHT, z=0., visibility=1.)
                      for name, index in JOINTS]
        except (KeyError, ValueError):
            points = []
        before = counter.stage
        observation, _ = counter.update(points, frame / SOURCE_FPS, WIDTH / HEIGHT)
        sampled += 1
        usable += observation is not None
        if before != counter.stage:
            stage_changes[f"{before}->{counter.stage}"] += 1
    return sampled, usable, counter.rep_count, dict(stage_changes)


def evaluate(zip_path: Path, reference_annotation: Path) -> dict:
    reference = json.loads(reference_annotation.read_text(encoding="utf-8-sig"))
    category = reference["annotations"][0]["motion_category3"]
    result_counts: Counter[int] = Counter()
    camera_results: dict[str, Counter[int]] = defaultdict(Counter)
    category_results: dict[str, Counter[int]] = defaultdict(Counter)
    transitions: Counter[str] = Counter()
    sessions: dict[str, list[int]] = defaultdict(list)
    matched = missing_csv = unreadable = 0
    with zipfile.ZipFile(zip_path) as archive:
        files = set(archive.namelist())
        for info in archive.infolist():
            if not info.filename.endswith("/video/annotation.json"):
                continue
            try:
                annotation = json.loads(archive.read(info).decode("utf-8-sig"))
                motions = annotation["annotations"]
                if len(motions) != 1 or motions[0]["motion_category3"] != category:
                    continue
                matched += 1
                camera_path = info.filename.rsplit("/video/", 1)[0]
                camera = camera_path.rsplit("/", 1)[-1]
                session = camera_path.rsplit("/", 1)[0]
                stem = Path(annotation["video_name"]).stem
                csv_name = f"{camera_path}/local_keypoints/{stem}.csv"
                if csv_name not in files:
                    missing_csv += 1
                    continue
                with archive.open(csv_name) as handle:
                    _, _, reps, changes = replay(handle)
            except (ValueError, KeyError, UnicodeError, csv.Error) as exc:
                unreadable += 1
                print(f"Could not read {info.filename}: {exc}", file=sys.stderr)
                continue
            sessions[session].append(reps)
            result_counts[reps] += 1
            camera_results[camera][reps] += 1
            category_results[motions[0]["motion_category4"]][reps] += 1
            transitions.update(changes)
    return {
        "matched_camera_labels": matched,
        "evaluated_camera_labels": sum(result_counts.values()),
        "unique_recordings": len(sessions),
        "one_rep_views_per_recording": dict(sorted(Counter(
            sum(reps == 1 for reps in views) for views in sessions.values()
        ).items())),
        "missing_csv": missing_csv, "unreadable": unreadable,
        "rep_counts": dict(sorted(result_counts.items())),
        "by_camera": {camera: dict(sorted(counts.items()))
                      for camera, counts in sorted(camera_results.items())},
        "by_form_label": {label: dict(sorted(counts.items()))
                          for label, counts in sorted(category_results.items())},
        "stage_transitions": dict(transitions),
        "interpretation": "One action interval is a proxy, not a verified one-repetition label.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", type=Path)
    parser.add_argument("reference_annotation", type=Path,
                        help="Sample annotation.json selecting a motion category")
    args = parser.parse_args()
    if not args.zip_path.is_file() or not args.reference_annotation.is_file():
        parser.error("Both the archive and reference annotation must exist")
    print(json.dumps(evaluate(args.zip_path, args.reference_annotation),
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
