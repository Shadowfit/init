"""저장된 pose_data.joint_coordinates 로 무릎각 판정(3D)을 2D 와 대조한다 — #217 판정축 측정.

설계: docs/decisions/pose-json-backfill-query.md §5-1. 이 스크립트는 그 문서가 정한 것만 잰다.

── 무엇을 재나 ──────────────────────────────────────────────────────────────────
  ① 각도는 **SQL 이 계산**한다(§4-1 a) — `->'$[i]'` 경로로 hip·knee·ankle 3점을 꺼내 3D/2D
     두 벌을 낸다. 수식은 angle_calculator.calculate_angle 의 사본이고(1e-8·clip 포함),
     같은 세션에서 «재계산 3D 좌우평균 → 트레일링 3프레임 평활» 이 저장된 smoothed_knee_angle
     과 0.5° 안에서 맞는 것을 자기검증으로 먼저 확인한다(안 맞으면 수식이 틀린 것).
  ② 3D−2D 차 분포 — 무릎별로, 그 무릎의 visibility 가 VISIBILITY_FLOOR(0.55, 코드의 프레임
     가시성 하한을 관절 하나에 적용한 것 — 새 임계값이 아니다) 위/아래로 나눠서.
  ③ 문턱 뒤집힘 — 평활 3D vs 평활 2D 가 BOTTOM(100)·STANDING(150) 기준으로 갈리는 프레임 수.
  ④ rep 카운트 — 판정 상태기계는 SQL 로 재현하지 않는다. **AI 서버의 StreamingSquatAnalyzer
     를 그대로** 저장된 랜드마크로 다시 돌린다: z 그대로(3D) 와 z=0(2D) 두 팔. 3D 팔이 저장된
     MAX(rep_number) 를 재현하는지가 두 번째 자기검증이다.
  ⑤ 좌우 비대칭 |L3D−R3D| — 양 무릎 다 보이는 프레임과 아닌 프레임을 나눠서. 판정 없음.

── 표본의 한계 (결과에 그대로 적는다) ────────────────────────────────────────────
  스톡 영상(Pexels류) → e1_walkthrough → R=1 적재. 실제 사람·실제 카메라지만 이 앱의 셀피
  파이프라인도 실사용자도 아니다. rep 0 세션은 pose_data 에 한 행도 없고(rep 단위 배치),
  rep 당 MAX_REP_FRAMES=60 트레일링 창 밖은 밀려난다 — «판정 프레임 전부» 가 아니다.

── 사용 ─────────────────────────────────────────────────────────────────────────
  cd ai-server && PYTHONPATH=. .venv/Scripts/python.exe ../loadtest/measure_pose_json_angle_axis.py \
      --sessions 108198-108205 --out ../loadtest/results/pose-json-backfill-2026-09-19
  MySQL 은 docker exec shadowfit-mysql 로 간다(MYSQL_ROOT_PASSWORD 는 .env 에서).
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

# ai-server 를 PYTHONPATH 로 두고 돈다 — 판정 상태기계를 복제하지 않고 원본을 쓰기 위해서다.
from app.core.squat_analyzer import StreamingSquatAnalyzer  # noqa: E402
from app.grpc.session_state import SessionState  # noqa: E402
from app.models.pose import Landmark  # noqa: E402

FLOOR = StreamingSquatAnalyzer.VISIBILITY_FLOOR
BOTTOM = StreamingSquatAnalyzer.BOTTOM_THRESHOLD
STANDING = StreamingSquatAnalyzer.STANDING_THRESHOLD

# ── SQL: 카드의 쿼리 그대로. 세션마다 앵커(created_at)를 넣어 파티션 프루닝을 유지한다 ──
_JOINT = {"lh": 23, "lk": 25, "la": 27, "rh": 24, "rk": 26, "ra": 28}


def _angle_expr(h: str, k: str, a: str, three_d: bool) -> str:
    dot = f"(({h}x-{k}x)*({a}x-{k}x)+({h}y-{k}y)*({a}y-{k}y)"
    n1 = f"POW({h}x-{k}x,2)+POW({h}y-{k}y,2)"
    n2 = f"POW({a}x-{k}x,2)+POW({a}y-{k}y,2)"
    if three_d:
        dot += f"+({h}z-{k}z)*({a}z-{k}z)"
        n1 += f"+POW({h}z-{k}z,2)"
        n2 += f"+POW({a}z-{k}z,2)"
    dot += ")"
    return f"DEGREES(ACOS(GREATEST(-1, LEAST(1, {dot} / (SQRT({n1})*SQRT({n2})+1e-8)))))"


def frame_sql(session_id: int) -> str:
    cols = ", ".join(
        f"joint_coordinates->'$[{idx}].{ax}' AS {name}{ax}"
        for name, idx in _JOINT.items()
        for ax in "xyz"
    )
    return f"""
SET @anchor = (SELECT MIN(created_at) FROM pose_data WHERE session_id = {session_id});
WITH pt AS (
  SELECT id, timestamp_sec, rep_number, smoothed_knee_angle,
         joint_coordinates->'$[25].visibility' AS lkv, joint_coordinates->'$[26].visibility' AS rkv,
         joint_coordinates AS jc, {cols}
  FROM pose_data WHERE session_id = {session_id} AND created_at = @anchor
)
SELECT id, timestamp_sec, rep_number, smoothed_knee_angle, lkv, rkv,
       {_angle_expr('lh','lk','la',True)} AS l3d, {_angle_expr('lh','lk','la',False)} AS l2d,
       {_angle_expr('rh','rk','ra',True)} AS r3d, {_angle_expr('rh','rk','ra',False)} AS r2d,
       jc
FROM pt ORDER BY timestamp_sec;
"""


def run_sql(sql: str, password: str) -> list[list[str]]:
    r = subprocess.run(
        ["docker", "exec", "-i", "shadowfit-mysql", "mysql", "-N", "-B", "--raw",
         "-uroot", f"-p{password}", "shadowfit"],
        input=sql.encode(), capture_output=True, check=True,
    )
    return [line.split("\t") for line in r.stdout.decode().splitlines() if line]


def fetch_frames(session_id: int, password: str) -> list[dict]:
    rows = []
    for r in run_sql(frame_sql(session_id), password):
        rows.append({
            "id": int(r[0]), "t": float(r[1]), "rep": int(r[2]), "stored": float(r[3]),
            "lkv": float(r[4]), "rkv": float(r[5]),
            "l3d": float(r[6]), "l2d": float(r[7]), "r3d": float(r[8]), "r2d": float(r[9]),
            "jc": json.loads(r[10]),
        })
    return rows


def trailing_mean(values: list[float], window: int = 3) -> list[float]:
    # StreamingSquatAnalyzer.process_frame 의 recent_raw_knees 와 같은 정의(트레일링, 최대 3개).
    out = []
    for i in range(len(values)):
        w = values[max(0, i - window + 1): i + 1]
        out.append(round(sum(w) / len(w), 2))
    return out


def replay_reps(frames: list[dict], drop_z: bool) -> int:
    """저장된 랜드마크를 AI 서버의 스트리밍 분석기에 그대로 다시 넣는다. drop_z 면 2D 팔."""
    analyzer = StreamingSquatAnalyzer()
    state = SessionState(session_id=0, exercise_id=1)
    for f in frames:
        lms = [Landmark(index=p["index"], x=p["x"], y=p["y"], z=0.0 if drop_z else p["z"],
                        visibility=p["visibility"]) for p in f["jc"]]
        analyzer.process_frame(state, lms)
    return state.rep_count


def quantiles(xs: list[float]) -> dict:
    if not xs:
        return {"n": 0}
    xs = sorted(xs)
    q = statistics.quantiles(xs, n=10) if len(xs) >= 2 else [xs[0]] * 9
    return {"n": len(xs), "p50": round(statistics.median(xs), 1), "p90": round(q[8], 1),
            "max": round(xs[-1], 1)}


def measure_session(sid: int, frames: list[dict]) -> dict:
    avg3d = [(f["l3d"] + f["r3d"]) / 2 for f in frames]
    avg2d = [(f["l2d"] + f["r2d"]) / 2 for f in frames]
    sm3d, sm2d = trailing_mean(avg3d), trailing_mean(avg2d)

    # ① 자기검증 — SQL 3D 평활 vs 저장값
    err = [abs(s - f["stored"]) for s, f in zip(sm3d, frames)]
    # ② 3D−2D, 무릎별·visibility 구간별
    diff = defaultdict(list)
    for f in frames:
        diff["L" + ("_vis" if f["lkv"] >= FLOOR else "_occl")].append(abs(f["l3d"] - f["l2d"]))
        diff["R" + ("_vis" if f["rkv"] >= FLOOR else "_occl")].append(abs(f["r3d"] - f["r2d"]))
    # ③ 문턱 뒤집힘
    flip_bottom = sum((a <= BOTTOM) != (b <= BOTTOM) for a, b in zip(sm3d, sm2d))
    flip_stand = sum((a >= STANDING) != (b >= STANDING) for a, b in zip(sm3d, sm2d))
    # ⑤ 비대칭
    asym = defaultdict(list)
    for f in frames:
        key = "both_vis" if f["lkv"] >= FLOOR and f["rkv"] >= FLOOR else "one_occl"
        asym[key].append(abs(f["l3d"] - f["r3d"]))
    # ④ rep 재생
    stored_reps = max(f["rep"] for f in frames)
    return {
        "session_id": sid, "frames": len(frames), "stored_reps": stored_reps,
        "knee_vis_mean": {"L": round(statistics.mean(f["lkv"] for f in frames), 2),
                          "R": round(statistics.mean(f["rkv"] for f in frames), 2)},
        "selfcheck_smoothed_vs_stored": {
            "mean_abs_err": round(statistics.mean(err), 2), "max_abs_err": round(max(err), 2),
            "within_0_5deg": sum(e < 0.5 for e in err)},
        "abs_3d_minus_2d": {k: quantiles(v) for k, v in sorted(diff.items())},
        "threshold_flips": {"bottom_100": flip_bottom, "standing_150": flip_stand},
        "asymmetry_L_minus_R_3d": {k: quantiles(v) for k, v in sorted(asym.items())},
        "replay_reps": {"3d": replay_reps(frames, drop_z=False),
                        "2d": replay_reps(frames, drop_z=True)},
    }


def parse_sessions(spec: str) -> list[int]:
    out: list[int] = []
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-")
            out.extend(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    return out


def main() -> int:
    p = argparse.ArgumentParser(description="#217 판정축 — 저장 JSON 으로 3D/2D 무릎각 대조")
    p.add_argument("--sessions", required=True, help="예: 108198-108205 또는 1,2,3")
    p.add_argument("--out", required=True, help="결과 폴더 (result.json 을 쓴다)")
    p.add_argument("--password", default=os.environ.get("MYSQL_ROOT_PASSWORD", ""))
    args = p.parse_args()
    if not args.password:
        print("MYSQL_ROOT_PASSWORD 가 없다 (--password 또는 env)", file=sys.stderr)
        return 2

    per_session = []
    for sid in parse_sessions(args.sessions):
        frames = fetch_frames(sid, args.password)
        if not frames:
            per_session.append({"session_id": sid, "frames": 0, "note": "행 없음 — rep 0 세션"})
            continue
        per_session.append(measure_session(sid, frames))

    # 전체 합산(프레임 단위) — 세션별 표와 같이 둔다
    flips = {"bottom_100": 0, "standing_150": 0}
    for s in per_session:
        if not s.get("frames"):
            continue
        for k in ("bottom_100", "standing_150"):
            flips[k] += s["threshold_flips"][k]
    result = {
        "design": "docs/decisions/pose-json-backfill-query.md §5-1",
        "constants_from_code": {"VISIBILITY_FLOOR": FLOOR, "BOTTOM": BOTTOM, "STANDING": STANDING},
        "per_session": per_session,
        "pooled_threshold_flips": flips,
        "total_frames": sum(s.get("frames", 0) for s in per_session),
    }
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
