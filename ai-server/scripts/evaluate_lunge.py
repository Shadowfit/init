"""전방 런지 촬영본을 같은 분석기로 재생하고 수동 정답과 비교한다.

python scripts/evaluate_lunge.py clip.mp4 --expected-left 5 --expected-right 5
카메라를 열거나 영상을 외부로 전송하지 않는다. 고정 FPS 영상만 지원한다.
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

from app.core.lunge_analyzer import StreamingLungeAnalyzer
from app.core.mediapipe_detector import PoseDetector
from app.grpc.session_state import SessionState


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--sample-fps", type=float, default=3.0)
    parser.add_argument("--expected-left", type=int)
    parser.add_argument("--expected-right", type=int)
    parser.add_argument("--trace", type=Path, help="프레임별 단계/각도/횟수 CSV 출력")
    args = parser.parse_args()
    if not args.video.is_file():
        parser.error("로컬 영상 파일이 필요합니다.")
    if not math.isfinite(args.sample_fps) or args.sample_fps <= 0:
        parser.error("sample-fps는 양수여야 합니다.")
    if (args.expected_left is None) != (args.expected_right is None):
        parser.error("좌우 정답을 모두 입력해주세요.")
    if any(n is not None and n < 0 for n in (args.expected_left, args.expected_right)):
        parser.error("정답 횟수는 0 이상이어야 합니다.")

    video = cv2.VideoCapture(str(args.video))
    fps = video.get(cv2.CAP_PROP_FPS)
    if not video.isOpened() or not math.isfinite(fps) or fps <= 0:
        video.release()
        parser.error("영상 FPS를 읽지 못했습니다.")
    state = SessionState(0, 2, exercise_type="lunge")
    analyzer = StreamingLungeAnalyzer()
    trace_file = args.trace.open("w", newline="", encoding="utf-8") if args.trace else None
    writer = csv.writer(trace_file) if trace_file else None
    if writer:
        writer.writerow(["time_sec", "phase", "lead_leg", "left_knee", "right_knee", "total", "left", "right"])
    detector = None
    index, accepted, skipped, calibrated, next_time = 0, 0, 0, 0, 0.
    try:
        detector = PoseDetector()
        while True:
            ok, image = video.read()
            if not ok:
                break
            timestamp = index / fps
            index += 1
            if timestamp + 1e-9 < next_time:
                continue
            next_time = timestamp + 1 / args.sample_fps
            landmarks = detector.detect(cv2.cvtColor(image, cv2.COLOR_BGR2RGB)) or []
            angles, _, _, _ = analyzer.process_frame(
                state, landmarks, timestamp_sec=timestamp,
                image_aspect_ratio=image.shape[1] / image.shape[0],
            )
            accepted += 1
            skipped += angles is None
            s = state.lunge_state
            calibrated += s.phase != "calibrating"
            if writer:
                writer.writerow([round(timestamp, 3), s.phase, s.lead_leg,
                                 *(angles or [None, None]), state.rep_count, s.left_reps, s.right_reps])
    finally:
        video.release()
        if detector:
            detector.close()
        if trace_file:
            trace_file.close()
    if not accepted:
        parser.error("읽을 수 있는 영상 프레임이 없습니다.")
    progress = state.lunge_state.progress()
    result = {
        "video": str(args.video), "source_fps": fps, "sample_fps": args.sample_fps,
        "analyzed_frames": accepted, "unusable_frames": skipped,
        "usable_frames": accepted - skipped, "calibrated_frames": calibrated,
        "evaluation_valid": calibrated > 0,
        "total_reps": state.rep_count, "left_reps": progress.left_reps,
        "right_reps": progress.right_reps, "thresholds": asdict(analyzer.thresholds),
    }
    if args.expected_left is not None:
        result["expected_total"] = args.expected_left + args.expected_right
        # 시작 자세를 한 번도 확보하지 못한 영상을 '정확도 100%, 0회'로 해석하지 않는다.
        result["absolute_error"] = abs(state.rep_count - result["expected_total"]) if calibrated else None
        result["left_absolute_error"] = abs(progress.left_reps - args.expected_left) if calibrated else None
        result["right_absolute_error"] = abs(progress.right_reps - args.expected_right) if calibrated else None
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if calibrated else 2


if __name__ == "__main__":
    sys.exit(main())
