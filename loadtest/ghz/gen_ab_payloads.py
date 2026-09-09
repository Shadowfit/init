"""gRPC vs WebClient A/B 페이로드 생성 (docs/decisions/grpc-webclient-empirical-comparison.md §9.3).

이 스크립트가 만드는 JSON **한 장이 두 팔에 그대로 쓰인다**:
  - gRPC 팔  : ghz 가 `--proto` 로 JSON → protobuf 로 바꿔 보낸다 (바이너리가 나감)
  - REST 팔  : k6 가 같은 파일을 HTTP 본문으로 그대로 보낸다 (JSON 이 나감)

같은 파일을 쓰는 게 이 rig 의 공정성 장치다. proto 필드명과 Pydantic 필드명이 이미 같게
설계돼 있어서(ai-server/app/models/internal_analysis.py 주석) 변환표가 필요 없다 — 두 팔의
«논리적 요청»이 한 글자도 다르지 않고, 달라지는 건 직렬화 형식뿐이다.

## 페이로드 두 종류와 그 이유

이 라운드는 «전송 계층» 을 재는 것이지 «분석» 을 재는 게 아니다. 그래서 두 페이로드 모두
AI 핸들러가 **일찍 반환하는 입력**을 고른다 — 검출기(98.7MB/개)를 잡지도, MediaPipe 를
돌리지도 않는다. 그러면 팔 사이 차이는 직렬화·전송·라우팅만 남는다.

  S (small) — StopAnalysis, 레지스트리에 없는 session_id
      핸들러가 registry.remove()=None → 곧바로 success=False 반환.
      본문이 session_id 하나뿐이라 «프로토콜 고정비» 만 잰다.

  L (large) — ReattachAnalysis, 분석기가 없는 exercise_id
      resolve_exercise_type()=None 이라 reference_poses 를 파싱하기 전에 반환한다.
      🔴 하지만 **디코드는 이미 끝난 뒤다** — gRPC 는 핸들러 진입 전에 protobuf 를,
      FastAPI 는 Pydantic 이 JSON 을 전부 파싱한다. 즉 이 팔이 정확히 재는 것은
      «큰 페이로드의 디코드 비용 차이»다. 지도교수의 «이진화라 빠르다» 주장(§1.4)이
      이 스케일에서 실제로 얼마인지가 여기서 나온다.

## 크기의 근거 (임의 값 아님)

  프레임 수 30   — reference_builder.build_reference_sequence 의 target_length 기본값이 30.
                   저장되는 기준 시퀀스가 실제로 이 길이다(리샘플 대표 1렙).
  랜드마크 33개  — MediaPipe Pose 의 랜드마크 수. joint_coordinates 는 이 33개를
                   {index,x,y,z,visibility} JSON 배열로 담는다(exercise_servicer._parse_reference_poses).

reference-style-and-caching.md §4 의 «30fps·2KB/프레임» 추정과도 같은 자리에 떨어진다.
"""

import argparse
import json
import random

LANDMARK_COUNT = 33          # MediaPipe Pose
DEFAULT_FRAMES = 30          # reference_builder.build_reference_sequence(target_length=30)


def _joint_coordinates(rng: random.Random) -> str:
    """한 프레임의 33개 랜드마크 — 실제 저장 모양(JSON 문자열)을 그대로 흉내낸다."""
    return json.dumps(
        [
            {
                "index": i,
                "x": round(rng.uniform(0.0, 1.0), 6),
                "y": round(rng.uniform(0.0, 1.0), 6),
                "z": round(rng.uniform(-1.0, 1.0), 6),
                "visibility": round(rng.uniform(0.5, 1.0), 6),
            }
            for i in range(LANDMARK_COUNT)
        ],
        separators=(",", ":"),
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--frames", type=int, default=DEFAULT_FRAMES)
    ap.add_argument(
        "--session-id",
        type=int,
        required=True,
        help="레지스트리에 없어야 하는 값. 실제로 도는 세션과 겹치면 남의 세션을 중단시킨다.",
    )
    ap.add_argument(
        "--unsupported-exercise-id",
        type=int,
        required=True,
        help="resolve_exercise_type() 이 None 을 주는 값 — L 팔이 분석에 안 들어가게 하는 장치. "
             "대상 서버의 supported_exercise_ids() 에 없는 값이어야 한다.",
    )
    ap.add_argument("--seed", type=int, default=20260910)
    args = ap.parse_args()

    rng = random.Random(args.seed)  # 판마다 같은 바이트가 나가야 판 간 비교가 성립한다

    stop = {"session_id": args.session_id}

    reattach = {
        "session_id": args.session_id,
        "exercise_id": args.unsupported_exercise_id,
        "persona": "BEGINNER",
        "initial_rep_count": 0,
        "elapsed_sec": 0.0,
        "session_nonce": "",
        "reference_poses": [
            {"timestamp_sec": round(i / 30.0, 4), "joint_coordinates": _joint_coordinates(rng)}
            for i in range(args.frames)
        ],
    }

    for name, body in (("stop", stop), ("reattach", reattach)):
        path = f"{args.out_dir}/{name}.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(body, f, separators=(",", ":"))
        with open(path, "rb") as f:
            size = len(f.read())
        print(f"  {name:9s} → {path}  ({size:,} bytes JSON)")


if __name__ == "__main__":
    main()
