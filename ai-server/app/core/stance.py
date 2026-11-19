"""스쿼트와 런지를 가르는 «앞뒤로 벌린 자세(split stance)» 판정 — 두 분석기가 공유한다.

왜 필요한가 (2026-10-19): 앱에서 스쿼트를 고르고 런지를 해도, 런지를 고르고 스쿼트를 해도
횟수와 점수가 나왔다. 두 분석기 모두 «무릎이 100° 근처까지 굽었다 펴졌는가» 를 세는데,
스쿼트와 런지 모두 그 조건을 만족하기 때문이다.

- 스쿼트 분석기는 두 무릎의 **평균**만 봐서, 런지 바닥 자세(두 무릎이 다 굽음)도 통과했다.
- 런지 분석기의 유일한 구분 신호였던 발목 간격(stagger_min=0.55)은 45° 촬영 스쿼트에서도
  0.6~0.7 이 나와 넘어갔다(squat_normal_02 · kkw_45-degree_frontview).

판정 신호 두 개 (둘 다 만족해야 «런지 자세»):

1. stance_ratio — 두 발목의 가로 간격 / 몸통 길이(어깨 중점↔골반 중점). 기존 stagger 와 같은 값.
2. thigh_asymmetry — 두 허벅지(골반→무릎)가 수직에서 기운 각도의 차이(도).
   런지 바닥에서는 앞 허벅지가 거의 수평, 뒤 허벅지가 거의 수직이라 차이가 크다.
   스쿼트는 두 허벅지가 같이 움직여 차이가 작다.

정면 스쿼트는 발 간격(1.3~1.5)이 커도 허벅지 차이가 작고(≤5°), 45° 스쿼트는 원근 때문에
허벅지 차이가 커질 수 있어도(최대 36°) 발 간격이 작다(≤0.69) — 한 신호로는 못 가르고
두 신호를 함께 봐야 갈린다.

문턱값 근거 — 저장소 실촬영 13개를 3fps 로 재생해, 바닥 근처 프레임(무릎 평균 ≤115° 또는
한쪽 ≤100°)에서 측정 (scratch 측정, 2026-10-19):

| 묶음 | 프레임 | 두 조건 동시 만족 |
|---|---|---|
| 런지 측면 3개(normal·sallow·walking) | 66 | 66 (100%) |
| 런지 45°(지원 범위 밖) | 29 | 27 |
| 스쿼트 9개(정면·45°·측면·얕음) | 97 | 0 |

- 발 간격: 스쿼트 중 허벅지 차이 ≥25° 인 프레임의 최대 0.69, 런지 측면 최소 0.79 → 사이 0.74.
- 허벅지 차이: 발 간격 ≥0.74 인 스쿼트 프레임의 최대 9°, 런지 측면 바닥 대부분 ≥37° → 25°.

MediaPipe 실행 방식이 앱(legacy solutions.Pose)과 측정(tasks PoseLandmarker, 같은 full 모델)
이 달라 수치가 조금 다를 수 있다. 표본도 촬영자 2명 수준이라 «검증 완료» 가 아니라
«지금 가진 영상 전부에서 갈린다» 는 근거다.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

# MediaPipe 랜드마크 인덱스
_SHOULDERS = (11, 12)
_HIPS = (23, 24)
_KNEES = (25, 26)
_ANKLES = (27, 28)

SPLIT_STANCE_MIN_RATIO = 0.74
SPLIT_STANCE_MIN_THIGH_ASYMMETRY = 25.0


@dataclass(frozen=True)
class StanceFeatures:
    stance_ratio: float
    thigh_asymmetry: float

    def is_split(self, min_ratio: float = SPLIT_STANCE_MIN_RATIO,
                 min_thigh_asymmetry: float = SPLIT_STANCE_MIN_THIGH_ASYMMETRY) -> bool:
        return self.stance_ratio >= min_ratio and self.thigh_asymmetry >= min_thigh_asymmetry


def _tilt_from_vertical(top: tuple[float, float], bottom: tuple[float, float]) -> float:
    return math.degrees(math.atan2(abs(top[0] - bottom[0]), abs(bottom[1] - top[1])))


def stance_features(points: dict[int, tuple[float, float]]) -> StanceFeatures | None:
    """가로 비율 보정이 끝난 이미지 좌표(points[index] = (x, y))에서 계산한다.

    양쪽 다리(골반·무릎·발목)와 어깨가 모두 있어야 한다. 한쪽 다리만 보이면 None —
    그 경우 «런지인지 스쿼트인지 모른다» 이지 «스쿼트다» 가 아니다.
    """
    needed = _SHOULDERS + _HIPS + _KNEES + _ANKLES
    if any(i not in points for i in needed):
        return None
    shoulder = tuple((points[11][a] + points[12][a]) / 2 for a in (0, 1))
    hip = tuple((points[23][a] + points[24][a]) / 2 for a in (0, 1))
    torso = math.dist(shoulder, hip)
    if torso < 1e-8:
        return None
    ratio = abs(points[27][0] - points[28][0]) / torso
    left = _tilt_from_vertical(points[23], points[25])
    right = _tilt_from_vertical(points[24], points[26])
    return StanceFeatures(stance_ratio=ratio, thigh_asymmetry=abs(left - right))
