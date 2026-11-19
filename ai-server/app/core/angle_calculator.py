"""관절 각도 계산 모듈."""

import numpy as np

from app.models.pose import Landmark
from app.utils.constants import EXERCISE_ANGLES, LANDMARK


def calculate_angle(a: Landmark, b: Landmark, c: Landmark, image_aspect_ratio: float = 1.0) -> float:
    """세 관절 좌표(a, b=피벗, c)로 각도를 계산한다 — **이미지 평면 2D**.

    z 를 섞지 않는 이유(2026-10-20 실촬영): MediaPipe 의 z 는 단안 추정값이라 45° 촬영에서 무릎
    최소각이 18° 처럼 불가능한 값이 나왔고, 싱크로율이 자세가 아니라 깊이 잡음에 좌우됐다.
    반복 판정(squat_counter)·3D 비교(frontend lib/motion)도 2D 로 재므로 셋이 같은 정의를 쓴다.

    x 는 너비, y 는 높이 기준 0~1 이라 세로 사진(너비/높이 0.56)에서는 x 가 눌려 각도가 틀어진다 —
    image_aspect_ratio(너비/높이)를 x 에 곱해 같은 길이 단위로 맞춘다. 모르면 1.0(보정 없음).

    Returns:
        각도 (0~180도)
    """
    ar = image_aspect_ratio if image_aspect_ratio and image_aspect_ratio > 0 else 1.0
    vec_ba = np.array([(a.x - b.x) * ar, a.y - b.y])
    vec_bc = np.array([(c.x - b.x) * ar, c.y - b.y])

    cosine = np.dot(vec_ba, vec_bc) / (
        np.linalg.norm(vec_ba) * np.linalg.norm(vec_bc) + 1e-8
    )
    cosine = np.clip(cosine, -1.0, 1.0)
    return float(np.degrees(np.arccos(cosine)))


def extract_angles(
    landmarks: list[Landmark], exercise_type: str, image_aspect_ratio: float = 1.0
) -> list[float]:
    """랜드마크에서 운동 유형에 해당하는 관절 각도들을 추출한다 (2D, 비율 보정 — calculate_angle 참고)."""
    angle_defs = EXERCISE_ANGLES.get(exercise_type)
    if not angle_defs:
        raise ValueError(f"지원하지 않는 운동: {exercise_type}")

    lm_map = {lm.index: lm for lm in landmarks}
    angles = []
    for name_a, name_b, name_c in angle_defs:
        a = lm_map[LANDMARK[name_a]]
        b = lm_map[LANDMARK[name_b]]
        c = lm_map[LANDMARK[name_c]]
        angles.append(round(calculate_angle(a, b, c, image_aspect_ratio), 2))
    return angles
