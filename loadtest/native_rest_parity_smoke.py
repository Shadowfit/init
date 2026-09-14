#!/usr/bin/env python3
"""5차 라운드 게이트 — 세 REST 팔(mirror / native / nested)이 같은 재부착에 같은 답을 내는가.

설계: docs/decisions/grpc-webclient-native-rest-round.md §5-4. native 는 pydantic 객체를 gRPC
서비서에 그대로 밀어 넣는 측정용 경로라(§2-2 방식 (i)), proto3 기본값에 기대는 서비서 코드가
pydantic 기본값에서도 같게 도는지를 **라운드 시작 때 실제 컨테이너에서** 한 번 확인한다.
단위 테스트(ai-server/tests/test_internal_analysis_rest.py)가 같은 것을 in-process 로 보지만,
그건 배포 이미지가 아니다 — 옛 이미지가 떠 있으면 /native 가 404 라 여기서 걸린다.

같은 세션에 mirror → native → nested 순으로 재부착을 보낸다:
  1번째: success=true, already_active=false (새 상태)
  2·3번째: success=true, already_active=true, rep_count 는 1번째의 initial_rep_count 그대로
끝에 /stop 으로 상태를 지워 검출기 풀을 돌려준다.

사용: python3 loadtest/native_rest_parity_smoke.py --url http://localhost:8000 \\
        --token $INTERNAL_API_TOKEN --session-id 900000001
종료코드 0 = 셋이 같다. 그 외 = 라운드를 시작하면 안 된다.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "ghz"))
from gen_ab_payloads import DEFAULT_FRAMES, _joint_coordinates  # noqa: E402

PREFIX = {
    "mirror": "/api/v1/internal/analysis",
    "native": "/api/v1/internal/analysis/native",
    "nested": "/api/v1/internal/analysis/native-nested",
}


def post(url: str, token: str, body: dict, worker: str = "0") -> tuple[int, dict | str]:
    data = json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}", "X-AI-Worker": worker},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.loads(res.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="ai-nginx 주소 (예: http://localhost:8000)")
    ap.add_argument("--token", required=True, help="INTERNAL_API_TOKEN")
    ap.add_argument("--session-id", type=int, required=True, help="레지스트리에 없어야 하는 값")
    ap.add_argument("--exercise-id", type=int, default=1, help="분석기가 있는 종목(기본 1=squat)")
    ap.add_argument("--frames", type=int, default=DEFAULT_FRAMES)
    args = ap.parse_args()

    rng = random.Random(20260914)
    text_frames = [
        {"timestamp_sec": round(i / 30.0, 4), "joint_coordinates": _joint_coordinates(rng)}
        for i in range(args.frames)
    ]
    base = {
        "session_id": args.session_id,
        "exercise_id": args.exercise_id,
        "persona": "BEGINNER",
        "initial_rep_count": 4,
        "elapsed_sec": 1.5,
        "session_nonce": "",
    }
    bodies = {
        "mirror": {**base, "reference_poses": text_frames},
        "native": {**base, "reference_poses": text_frames},
        "nested": {**base, "reference_poses": [
            {"timestamp_sec": f["timestamp_sec"], "joint_coordinates": json.loads(f["joint_coordinates"])}
            for f in text_frames
        ]},
    }
    sizes = {k: len(json.dumps(v, separators=(",", ":")).encode()) for k, v in bodies.items()}

    results = {}
    rc = 0
    for arm in ("mirror", "native", "nested"):
        status, body = post(f"{args.url}{PREFIX[arm]}/reattach", args.token, bodies[arm])
        results[arm] = (status, body)
        print(f"{arm:7s} HTTP {status}  body={sizes[arm]:,} B  → {body}")
        if status != 200 or not isinstance(body, dict):
            rc = 1

    # 상태를 지운다 — 어느 경로든 같은 레지스트리. 실패해도 판정엔 안 넣는다(풀 하나가 남을 뿐).
    st, sb = post(f"{args.url}{PREFIX['native']}/stop", args.token, {"session_id": args.session_id})
    print(f"stop    HTTP {st} → {sb}")

    if rc:
        print("🔴 한 팔 이상이 200/JSON 이 아니다 — 이미지가 옛것이거나(/native 404) 토큰이 틀렸다")
        return rc
    m, n, d = (results[a][1] for a in ("mirror", "native", "nested"))
    checks = [
        (m["success"] is True and m["already_active"] is False, "mirror(1번째): success=true · already_active=false"),
        (n["success"] is True and n["already_active"] is True, "native(2번째): success=true · already_active=true"),
        (d == n, "nested(3번째) == native(2번째)"),
        (m["rep_count"] == n["rep_count"] == 4, "rep_count 가 initial_rep_count(4) 그대로 보존"),
        (sizes["mirror"] == sizes["native"] > sizes["nested"], "본문 크기: mirror = native > nested"),
    ]
    for ok, what in checks:
        print(("✅ " if ok else "🔴 ") + what)
        rc |= 0 if ok else 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
