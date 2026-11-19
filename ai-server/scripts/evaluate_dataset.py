"""Evaluate labeled squat/forward-lunge clips with the existing per-video analyzers.

python scripts/evaluate_dataset.py ../samples/validation/labels.csv
Only clips with usable tracking and a human count contribute to accuracy metrics.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import subprocess
import sys


INPUT_FIELDS = (
    "subject_id", "video", "exercise", "view", "mirrored",
    "expected_total", "expected_left", "expected_right", "notes",
)
OUTPUT_FIELDS = (
    *INPUT_FIELDS, "evaluation_valid", "sampled_frames", "usable_frames",
    "valid_frame_ratio", "detected_total", "detected_left", "detected_right",
    "absolute_error", "left_absolute_error", "right_absolute_error", "error",
)
MIN_VALID_FRAME_RATIO = 0.7  # 스쿼트 단일 영상 평가기와 같은 운영상 품질 기준


def count(value: str, field: str, line: int) -> int | None:
    if not value.strip():
        return None
    try:
        result = int(value)
    except ValueError as exc:
        raise ValueError(f"{line}행 {field}: 0 이상의 정수를 입력해주세요.") from exc
    if result < 0:
        raise ValueError(f"{line}행 {field}: 0 이상의 정수를 입력해주세요.")
    return result


def load_manifest(path: Path) -> list[tuple[dict[str, str], Path, tuple[int | None, int | None, int | None]]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        missing = set(INPUT_FIELDS) - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"CSV 열이 없습니다: {', '.join(sorted(missing))}")
        clips = []
        for line, source in enumerate(reader, start=2):
            row = {field: (source[field] or "").strip() for field in INPUT_FIELDS}
            if not all(row[field] for field in ("subject_id", "video", "exercise", "view")):
                raise ValueError(f"{line}행: subject_id, video, exercise, view가 필요합니다.")
            if row["exercise"] not in ("squat", "lunge"):
                raise ValueError(f"{line}행 exercise: squat 또는 lunge만 지원합니다.")
            if row["mirrored"].lower() not in ("true", "false"):
                raise ValueError(f"{line}행 mirrored: true 또는 false를 입력해주세요.")
            row["mirrored"] = row["mirrored"].lower()
            expected = tuple(count(row[field], field, line) for field in
                             ("expected_total", "expected_left", "expected_right"))
            total, left, right = expected
            if row["exercise"] == "squat" and (left is not None or right is not None):
                raise ValueError(f"{line}행: 스쿼트는 좌우 정답을 비워주세요.")
            if row["exercise"] == "lunge" and (
                (left is None) != (right is None)
                or (total is not None and (left is None or total != left + right))
            ):
                raise ValueError(f"{line}행: 런지 좌우 정답을 모두 쓰고 총횟수를 합계와 맞춰주세요.")
            if row["exercise"] == "lunge" and total is None and left is not None and right is not None:
                row["expected_total"] = str(left + right)
                expected = (left + right, left, right)
            video = Path(row["video"])
            if not video.is_absolute():
                video = path.parent / video
            if not video.is_file():
                raise ValueError(f"{line}행: 영상 파일이 없습니다: {video}")
            clips.append((row, video, expected))
    if not clips:
        raise ValueError("CSV에 영상 행이 없습니다.")
    return clips


def evaluate(row: dict[str, str], video: Path,
             expected: tuple[int | None, int | None, int | None], sample_fps: float) -> dict:
    total, left, right = expected
    script = Path(__file__).with_name(
        "evaluate_squat.py" if row["exercise"] == "squat" else "evaluate_lunge.py"
    )
    command = [sys.executable, str(script), str(video), "--sample-fps", str(sample_fps)]
    if row["exercise"] == "squat" and total is not None:
        command.extend(("--expected", str(total)))
    if row["exercise"] == "lunge" and left is not None and right is not None:
        command.extend(("--expected-left", str(left), "--expected-right", str(right)))
    for attempt in range(2):
        process = subprocess.run(command, capture_output=True, text=True, check=False)
        try:
            result = json.loads(process.stdout)
            break
        except json.JSONDecodeError:
            if attempt == 1:
                detail = (process.stderr or process.stdout)[-400:] or "평가 출력이 JSON이 아닙니다."
                return {**row, "error": f"평가 프로세스 종료 코드 {process.returncode}: {detail}"}
    sampled = result.get("sampled_frames", result.get("analyzed_frames", 0))
    usable = result.get("usable_frames", 0)
    valid_ratio = usable / sampled if sampled else 0
    evaluation_valid = bool(result.get("evaluation_valid")) and valid_ratio >= MIN_VALID_FRAME_RATIO
    output = {
        **row,
        "evaluation_valid": evaluation_valid,
        "sampled_frames": sampled,
        "usable_frames": usable,
        "valid_frame_ratio": round(valid_ratio, 3),
        "detected_total": result.get("reps", result.get("total_reps")),
        "detected_left": result.get("left_reps", ""),
        "detected_right": result.get("right_reps", ""),
        "absolute_error": result.get("absolute_error", "") if evaluation_valid else "",
        "left_absolute_error": result.get("left_absolute_error", "") if evaluation_valid else "",
        "right_absolute_error": result.get("right_absolute_error", "") if evaluation_valid else "",
        "error": "" if process.returncode in (0, 2) else process.stderr[-400:],
    }
    if row["exercise"] == "lunge" and row["mirrored"] == "true":
        # 영상이 좌우 반전되면 탐지기의 좌우가 참가자의 실제 좌우와 바뀔 수 있다.
        # 총횟수만 자동 비교하고 좌우 오차는 원본 방향 확인 후 수동으로 해석한다.
        output["left_absolute_error"] = output["right_absolute_error"] = ""
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--sample-fps", type=float, default=3.0)
    args = parser.parse_args()
    if not math.isfinite(args.sample_fps) or args.sample_fps <= 0:
        parser.error("sample-fps는 양수여야 합니다.")
    try:
        clips = load_manifest(args.manifest.resolve())
    except (OSError, ValueError) as exc:
        parser.error(str(exc))

    results = []
    for row, video, expected in clips:
        print(f"Evaluating: {row['video']} ({row['exercise']})", file=sys.stderr)
        results.append(evaluate(row, video, expected, args.sample_fps))
    output = args.output or args.manifest.with_name("results.csv")
    with output.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=OUTPUT_FIELDS)
        writer.writeheader()
        writer.writerows(results)

    labeled = [r for r in results if r.get("evaluation_valid") and not r.get("error")
               and r["expected_total"].strip()]
    errors = [int(r["absolute_error"]) for r in labeled]
    sided = [r for r in labeled if r["exercise"] == "lunge" and r["mirrored"] == "false"]
    left_errors = [int(r["left_absolute_error"]) for r in sided]
    right_errors = [int(r["right_absolute_error"]) for r in sided]
    print(json.dumps({
        "clips": len(results),
        "evaluation_valid": sum(bool(r.get("evaluation_valid")) for r in results),
        "labeled_valid_clips": len(labeled),
        "exact_match_clips": sum(error == 0 for error in errors),
        "mean_absolute_error": round(sum(errors) / len(errors), 3) if errors else None,
        "lunge_side_labeled_clips": len(sided),
        "lunge_both_sides_exact_clips": sum(l == 0 and r == 0 for l, r in zip(left_errors, right_errors)),
        "mean_left_absolute_error": round(sum(left_errors) / len(left_errors), 3) if left_errors else None,
        "mean_right_absolute_error": round(sum(right_errors) / len(right_errors), 3) if right_errors else None,
        "processing_errors": sum(bool(r.get("error")) for r in results),
        "output": str(output),
    }, ensure_ascii=False, indent=2))
    return 1 if any(r.get("error") for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
