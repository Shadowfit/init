"""실촬영 좌표 기록(frontend/lib/motion/fixtures)으로 실시간 싱크로율(compute_sync_rate)을 재현한다 — τ·가중치 보정용(2026-10-20).

준비: 기준 좌표를 DB 에서 한 줄 JSON 으로 뽑아 ai-server/_ref1.jsonl 에 둔다.
  docker exec shadowfit-mysql mysql -u shadowfit -pshadowfit shadowfit -N -B -e \
    "SELECT CONCAT('{\"t\":', timestamp_sec, ',\"lm\":', joint_coordinates, '}') FROM exercise_references WHERE exercise_id=1 ORDER BY timestamp_sec" > _ref1.jsonl
실행(도커 이미지, 저장소 루트를 /src 로 마운트):
  docker run --rm -v C:/dev/shadowfit:/src -w /src/ai-server --entrypoint python shadowfit-shadowfit-ai scripts/sync_rate_harness.py
기준 좌표에 비율 표식(index=-1)이 있으면 그 값을, 없으면(옛 영상 기준) REF_ASPECT_FALLBACK 을 쓴다.

2026-10-21 마네킹 기준(시상면 투영 배율 0.45/0.45, 최저 무릎 71°) 결과 — 깊은 반복(무릎 48~77°) 87~95,
평행 근처(81~85°) 76~87, 얕은 반복(93°) 64. 투영 배율이 0.35/0.45 로 비틀렸던 기준(무릎 77°로 읽힘)에선
같은 얕은 반복이 75 였다.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))   # ai-server/ 를 import 루트로 (다른 scripts/ 와 같다)

from app.models.pose import Landmark
from app.core.angle_calculator import extract_angles
from app.core.dtw_calculator import compute_sync_rate, compute_dtw_distance
from app.config import settings

REF_ASPECT_FALLBACK = 1.0   # 표식(index=-1, image_aspect_ratio)이 없는 옛 기준 좌표일 때
ref = [json.loads(l) for l in open('_ref1.jsonl', encoding='utf-8')]
def _ref_angles(r):
    aspect = next((p['image_aspect_ratio'] for p in r['lm'] if p.get('index') == -1 and p.get('image_aspect_ratio')), REF_ASPECT_FALLBACK)
    lms = [Landmark(index=p['index'], x=p['x'], y=p['y'], z=p['z'], visibility=p['visibility']) for p in r['lm'] if p.get('index', -1) >= 0]
    return extract_angles(lms, 'squat', aspect)
ref_angles = [_ref_angles(r) for r in ref]
print('reference frames', len(ref_angles), '| tau', settings.SYNC_RATE_TAU_DEG, '| 기준 무릎최소', round(min((a[0] + a[1]) / 2 for a in ref_angles)), '고관절최소', round(min((a[2] + a[3]) / 2 for a in ref_angles)))
F = '/src/frontend/lib/motion/fixtures/'
for name in ['squat_normal_01', 'squat_normal_02', 'squat_side_01']:
    rec = json.load(open(F + name + '.json', encoding='utf-8'))
    reps, cur, prev = [], [], 0
    for fr in rec['frames']:
        if fr['lm']:
            lms = [Landmark(index=i, x=p[0], y=p[1], z=p[2], visibility=p[3]) for i, p in enumerate(fr['lm'])]
            cur.append(extract_angles(lms, 'squat', rec['aspect']))
        if fr['reps'] > prev:
            prev = fr['reps']; reps.append(cur); cur = []
    out = []
    for r in reps:
        if len(r) < 2:
            continue
        out.append((len(r), round(compute_dtw_distance(ref_angles, r), 1), compute_sync_rate(ref_angles, r), round(min((x[0] + x[1]) / 2 for x in r))))
    print(f"{name:16} (프레임수, 평균각도차°, 점수, 무릎최소): {out}")
