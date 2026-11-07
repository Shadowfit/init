"""실촬영 좌표 기록(frontend/lib/motion/fixtures)으로 실시간 싱크로율(compute_sync_rate)을 재현한다 — τ·가중치 보정용(2026-10-20, 2026-10-21 갱신).

준비: 기준 좌표를 DB 에서 한 줄 JSON 으로 뽑아 ai-server/_ref1.jsonl 에 둔다(다 쓰면 지운다 — 커밋 대상 아님).
  docker exec shadowfit-mysql mysql -u shadowfit -pshadowfit shadowfit -N -B -e \
    "SELECT CONCAT('{\"t\":', timestamp_sec, ',\"lm\":', joint_coordinates, '}') FROM exercise_references WHERE exercise_id=1 ORDER BY timestamp_sec" > _ref1.jsonl
실행(도커 이미지, 저장소 루트를 /src 로 마운트):
  docker run --rm -v C:/dev/shadowfit:/src -w /src/ai-server --entrypoint python shadowfit-shadowfit-ai scripts/sync_rate_harness.py
기준 좌표에 비율 표식(index=-1)이 있으면 그 값을, 없으면(옛 영상 기준) REF_ASPECT_FALLBACK 을 쓴다.

실시간 버퍼를 흉내 낸다(squat_analyzer → pose.py): 한 반복은 직전 반복 완료 뒤 **무릎 평균이 152° 아래로 내려간
첫 프레임**(선 자세 판정 standing_knee 160 − margin 8 — 그 앞의 선 프레임은 ready→descending 에서 버퍼가 비워져
안 남는다)부터 **그 반복의 완료 프레임**까지다. 프레임 시각은 기록의 t 를 그대로 넘긴다(궤적 항이 시간축으로 편다).
열 «솎음 3/4/5f» 는 같은 반복을 3·4·5프레임으로 찍었다고 치고(첫·최저점·마지막 프레임은 남기고 나머지는 고르게)
다시 잰 점수다 — 찍힌 프레임 수에 점수가 얼마나 흔들리는지 본다.

2026-10-21 마네킹 기준(시상면 투영 배율 0.45/0.45, 최저 무릎 71.2°·고관절 62.7°), 허용 무릎 5°·고관절 8°, τ 24 결과 —
깊고 바른 반복(무릎 48~66°) 88.7~94.8, 무릎 76~78° 81~86.5, 81° 76, 84° 68.5, 93° 47. 예전 식(허용 8/10°, τ 30,
궤적을 찍힌 프레임 그대로·양방향으로)은 깊은 반복이 프레임 수에 따라 78~92, 84° 77, 93° 58 이었다.
솎았을 때 점수 변화 |Δ| 평균 1.9 · 최대 9.7 — 최대는 무릎 84° 반복에서 최저점 바로 옆 프레임이 빠진 경우로,
궤적 항이 아니라 최저점 항의 refine_min(이웃 세 점 등간격 가정)이 바뀐 탓이다. 등간격 가정(시각 없이 ~3fps)으로
잰 점수(열 «등간격가정»)는 기록 시각을 쓴 점수와 0.7점 안에서 같다.
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))   # ai-server/ 를 import 루트로 (다른 scripts/ 와 같다)

from app.models.pose import Landmark
from app.core.angle_calculator import extract_angles
from app.core.dtw_calculator import compute_sync_rate, compute_dtw_distance, refine_min
from app.config import settings

REF_ASPECT_FALLBACK = 1.0   # 표식(index=-1, image_aspect_ratio)이 없는 옛 기준 좌표일 때
DESCENT_KNEE_DEG = 152.0    # 이 아래로 내려간 프레임부터 반복 버퍼에 담는다(선 자세 160 − 여유 8)
THIN_COUNTS = (3, 4, 5)

ref = [json.loads(l) for l in open('_ref1.jsonl', encoding='utf-8')]
def _ref_angles(r):
    aspect = next((p['image_aspect_ratio'] for p in r['lm'] if p.get('index') == -1 and p.get('image_aspect_ratio')), REF_ASPECT_FALLBACK)
    lms = [Landmark(index=p['index'], x=p['x'], y=p['y'], z=p['z'], visibility=p['visibility']) for p in r['lm'] if p.get('index', -1) >= 0]
    return extract_angles(lms, 'squat', aspect)
ref_angles = [_ref_angles(r) for r in ref]


def _knee(a):
    return (a[0] + a[1]) / 2


def _hip(a):
    return (a[2] + a[3]) / 2


def live_reps(rec):
    """[(시각 리스트, 각도 리스트)] — 실시간 버퍼처럼 «내려가기 시작한» 프레임부터 완료 프레임까지."""
    reps, cur, prev = [], [], 0
    for fr in rec['frames']:
        if fr['lm']:
            lms = [Landmark(index=i, x=p[0], y=p[1], z=p[2], visibility=p[3]) for i, p in enumerate(fr['lm'])]
            a = extract_angles(lms, 'squat', rec['aspect'])
            if cur or _knee(a) < DESCENT_KNEE_DEG:
                cur.append((fr['t'], a))
        if fr['reps'] > prev:
            prev = fr['reps']
            reps.append(([t for t, _ in cur], [a for _, a in cur]))
            cur = []
    return reps


def thin(n, bottom, k):
    """n 프레임 중 k 개 인덱스 — 첫·최저점·마지막은 남기고 나머지는 시간상 고르게."""
    keep = {0, bottom, n - 1}
    for c in np.linspace(0, n - 1, k + 2)[1:-1]:
        if len(keep) >= k:
            break
        keep.add(int(round(c)))
    i = 0
    while len(keep) < k:
        keep.add(i)
        i += 1
    return sorted(keep)


print(f"기준 {len(ref_angles)}프레임 | τ {settings.SYNC_RATE_TAU_DEG} | 기준 무릎최저 {refine_min([_knee(a) for a in ref_angles]):.1f}° "
      f"고관절최저 {refine_min([_hip(a) for a in ref_angles]):.1f}°")
print(f"{'기록':16} {'rep':>3} {'프레임':>4} {'길이s':>5} {'무릎최저':>6} {'고관절최저':>7} {'궤적°':>5} {'점수':>5} "
      f"{'등간격가정':>8} | 솎음 " + ' '.join(f'{k}f'.rjust(5) for k in THIN_COUNTS))
F = '/src/frontend/lib/motion/fixtures/'
moves = []
for name in ['squat_normal_01', 'squat_normal_02', 'squat_side_01']:
    rec = json.load(open(F + name + '.json', encoding='utf-8'))
    for no, (ts, r) in enumerate(live_reps(rec), start=1):
        if len(r) < 2:
            continue
        score = compute_sync_rate(ref_angles, r, user_times=ts)
        uniform = compute_sync_rate(ref_angles, r)
        knees = [_knee(a) for a in r]
        bottom = int(np.argmin(knees))
        thinned = []
        for k in THIN_COUNTS:
            if len(r) <= k:
                thinned.append('    -')
                continue
            idx = thin(len(r), bottom, k)
            s = compute_sync_rate(ref_angles, [r[i] for i in idx], user_times=[ts[i] for i in idx])
            moves.append(abs(s - score))
            thinned.append(f'{s:5.1f}')
        print(f"{name:16} {no:3d} {len(r):4d} {ts[-1] - ts[0]:5.2f} {refine_min(knees):6.1f} {refine_min([_hip(a) for a in r]):7.1f} "
              f"{compute_dtw_distance(ref_angles, r, user_times=ts):5.1f} {score:5.1f} {uniform:8.1f} | 솎음 " + ' '.join(thinned))
if moves:
    print(f"솎았을 때 점수 변화 |Δ| 평균 {np.mean(moves):.1f} · 최대 {np.max(moves):.1f} (표본 {len(moves)})")
