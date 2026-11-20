"""실촬영 영상을 3fps 로 재생해 «앱이 받는 것과 같은 모양» 의 좌표 기록(JSON)으로 내보낸다.

python scripts/export_landmarks.py squat ../samples/validation/2026-09-21/squat_normal_01.mp4 out.json
python scripts/export_landmarks.py lunge ../samples/validation/lunge_normal_01.mp4 out.json

3D 운동 비교(frontend/lib/motion)를 «실제 데이터» 로 검증하려는 용도다. 앱은 AI 응답의
landmarks·image_aspect_ratio·rep_count 를 프레임마다 기록하는데, 이 스크립트는 같은 분석기
(SquatCounter / StreamingLungeAnalyzer)를 돌려 같은 값을 만든다 — 폰 없이 파이프라인을 시험한다.

출력 형식 (frontend/lib/motion/types.ts 의 MotionRecording):
  {"exercise": "squat", "source": "...", "aspect": 0.5625,
   "frames": [{"t": 0.0, "lm": [[x,y,z,v] × 33], "reps": 0, "lead": null}, ...]}
"""
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2

from app.core.lunge_analyzer import StreamingLungeAnalyzer
from app.core.mediapipe_detector import PoseDetector
from app.core.squat_counter import SquatCounter
from app.grpc.session_state import SessionState

SAMPLE_FPS = 3.0


def export(exercise: str, video_path: Path) -> dict:
    video = cv2.VideoCapture(str(video_path))
    fps = video.get(cv2.CAP_PROP_FPS)
    if not video.isOpened() or not math.isfinite(fps) or fps <= 0:
        raise SystemExit(f"영상을 열 수 없습니다: {video_path}")
    detector = PoseDetector()
    counter = SquatCounter() if exercise == "squat" else None
    state = SessionState(0, 2, exercise_type="lunge") if exercise == "lunge" else None
    analyzer = StreamingLungeAnalyzer() if exercise == "lunge" else None
    frames, aspect = [], None
    index, next_time = 0, 0.0
    try:
        while True:
            ok, image = video.read()
            if not ok:
                break
            timestamp = index / fps
            index += 1
            if timestamp + 1e-9 < next_time:
                continue
            next_time = timestamp + 1 / SAMPLE_FPS
            aspect = image.shape[1] / image.shape[0]
            landmarks = detector.detect(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
            if exercise == "squat":
                counter.update(landmarks, timestamp, aspect)
                reps, lead = counter.rep_count, None
            else:
                analyzer.process_frame(state, landmarks or [], timestamp_sec=timestamp,
                                       image_aspect_ratio=aspect)
                reps, lead = state.rep_count, state.lunge_state.lead_leg
            if not landmarks:
                frames.append({"t": round(timestamp, 3), "lm": None, "reps": reps, "lead": lead})
                continue
            frames.append({
                "t": round(timestamp, 3),
                "lm": [[round(p.x, 4), round(p.y, 4), round(p.z, 4), round(p.visibility, 3)]
                       for p in landmarks],
                "reps": reps, "lead": lead,
            })
    finally:
        video.release()
        detector.close()
    return {"exercise": exercise, "source": video_path.name, "aspect": round(aspect or 0, 4),
            "frames": frames}


def main() -> int:
    if len(sys.argv) != 4 or sys.argv[1] not in ("squat", "lunge"):
        print(__doc__)
        return 2
    result = export(sys.argv[1], Path(sys.argv[2]))
    Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")),
                                 encoding="utf-8")
    detected = sum(f["lm"] is not None for f in result["frames"])
    print(f"{result['source']}: {len(result['frames'])}프레임 중 {detected} 검출, "
          f"횟수 {result['frames'][-1]['reps']}, 비율 {result['aspect']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
