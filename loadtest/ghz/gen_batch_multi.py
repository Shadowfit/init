#!/usr/bin/env python3
"""ghz 다세션 데이터 생성기 — 병목 귀속 재측정용 (single-hot-session 아티팩트 제거).

단일 session_id=801 에 모든 INSERT 가 몰리면 인덱스 리프 페이지 래치·redo 커밋이
직렬화돼 가짜 천장이 생긴다(귀속 분석 2026-06-12). 실제 DAU 1,000 은 서로 다른
수천 세션이 각자 다른 인덱스 구간에 INSERT → 이 경합이 없다. 그래서 ghz 가
round-robin 으로 N개 세션에 분산하도록 **메시지 배열**을 만든다.

ghz 는 --data-file 의 JSON 이 배열이면 요청마다 다음 원소를 순환 사용한다.

사용:
  python gen_batch_multi.py --sessions 901-1900 --reps 25 --out batch_multi.json
  (901~1900 = seed 세션 1,000개. DB 에 존재해야 FK 통과 — exercise_sessions 601~5911 보유)
"""
import argparse
import json


FEEDBACK_TYPES = ["", "", "KNEE_OUT", "BACK_BENT", "HIP_HIGH", "KNEE_IN", "", "KNEE_OUT"]


def make_landmarks(seed: int) -> str:
    landmarks = []
    for i in range(33):
        base = (seed * 31 + i * 7) % 1000 / 1000.0
        landmarks.append({
            "x": round(0.30 + base * 0.40, 6),
            "y": round(0.20 + ((base * 17) % 1.0) * 0.60, 6),
            "z": round(-0.25 + ((base * 13) % 1.0) * 0.50, 6),
            "visibility": round(0.85 + ((base * 11) % 1.0) * 0.15, 6),
        })
    return json.dumps(landmarks, separators=(",", ":"))


def make_pose_data(reps: int) -> list:
    pose = []
    for f in range(reps):
        pose.append({
            "timestampSec": round(f * 0.1, 1),
            "jointCoordinates": make_landmarks(f),
            "syncRate": round(45.0 + (f * 7 % 50), 2),
            "feedbackMessage": FEEDBACK_TYPES[f % len(FEEDBACK_TYPES)],
        })
    return pose


def parse_sessions(spec: str) -> list:
    lo, hi = spec.split("-")
    return list(range(int(lo), int(hi) + 1))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", default="901-1900", help="세션 id 범위 lo-hi (DB 존재 필수)")
    ap.add_argument("--reps", type=int, default=25, help="batch 내 프레임 수 = R")
    ap.add_argument("--out", default="batch_multi.json")
    args = ap.parse_args()

    sessions = parse_sessions(args.sessions)
    pose = make_pose_data(args.reps)  # payload 동일 — 세션 라우팅만 변수
    messages = [{"sessionId": sid, "poseData": pose} for sid in sessions]
    payload = json.dumps(messages, separators=(",", ":"))

    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(payload)

    size_mb = len(payload.encode("utf-8")) / 1024 / 1024
    print(f"wrote {args.out}: sessions={len(sessions)} ({sessions[0]}~{sessions[-1]}) "
          f"reps={args.reps} size={size_mb:.1f}MB")


if __name__ == "__main__":
    main()
