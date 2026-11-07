"""Post-processing filters for MediaPipe pose landmarks."""

from __future__ import annotations

from dataclasses import dataclass

from app.models.pose import Landmark
from app.utils.constants import LANDMARK


SQUAT_TRACKED_POINTS = (
    "LEFT_SHOULDER",
    "RIGHT_SHOULDER",
    "LEFT_HIP",
    "RIGHT_HIP",
    "LEFT_KNEE",
    "RIGHT_KNEE",
    "LEFT_ANKLE",
    "RIGHT_ANKLE",
)


@dataclass(frozen=True)
class NormalizedRoi:
    """Normalized region of interest used to keep the tracked person centered."""

    min_x: float
    min_y: float
    max_x: float
    max_y: float

    def contains(self, x: float, y: float) -> bool:
        return self.min_x <= x <= self.max_x and self.min_y <= y <= self.max_y


DEFAULT_SQUAT_ROI = NormalizedRoi(min_x=0.38, min_y=0.4, max_x=0.78, max_y=0.76)


@dataclass(frozen=True)
class PoseBounds:
    """Normalized body bounds for the landmarks used by the squat analyzer."""

    center_x: float
    center_y: float
    width: float
    height: float
    average_visibility: float

    @property
    def aspect_ratio(self) -> float:
        return self.width / (self.height + 1e-8)


def get_squat_pose_bounds(landmarks: list[Landmark]) -> PoseBounds:
    """Return bounds for core lower-body squat landmarks."""
    tracked = [landmarks[LANDMARK[name]] for name in SQUAT_TRACKED_POINTS]
    xs = [landmark.x for landmark in tracked]
    ys = [landmark.y for landmark in tracked]
    visibility = [landmark.visibility for landmark in tracked]
    return PoseBounds(
        center_x=sum(xs) / len(xs),
        center_y=sum(ys) / len(ys),
        width=max(xs) - min(xs),
        height=max(ys) - min(ys),
        average_visibility=sum(visibility) / len(visibility),
    )


def is_plausible_squat_pose(
    landmarks: list[Landmark],
    *,
    min_visibility: float = 0.65,
    min_height: float = 0.2,
    min_width: float = 0.1,
    max_width: float = 0.34,
    max_aspect_ratio: float = 0.9,
    roi: NormalizedRoi | None = DEFAULT_SQUAT_ROI,
) -> bool:
    """Reject common rack/equipment false positives before squat analysis."""
    bounds = get_squat_pose_bounds(landmarks)
    if bounds.average_visibility < min_visibility:
        return False
    if bounds.height < min_height or bounds.width < min_width:
        return False
    if bounds.width > max_width:
        return False
    if bounds.aspect_ratio > max_aspect_ratio:
        return False
    if roi and not roi.contains(bounds.center_x, bounds.center_y):
        return False
    return True


class PoseTargetTracker:
    """Keep pose analysis locked on one plausible person across video frames."""

    def __init__(
        self,
        max_center_jump: float = 0.28,
        roi: NormalizedRoi | None = DEFAULT_SQUAT_ROI,
        *,
        min_width: float = 0.1,
        max_width: float = 0.34,
        max_aspect_ratio: float = 0.9,
    ) -> None:
        self.max_center_jump = max_center_jump
        self.roi = roi
        self.min_width = min_width
        self.max_width = max_width
        self.max_aspect_ratio = max_aspect_ratio
        self._last_center: tuple[float, float] | None = None

    def filter(self, landmarks: list[Landmark] | None) -> list[Landmark] | None:
        """Return landmarks only when they look like the tracked squat subject."""
        if landmarks is None or not is_plausible_squat_pose(
            landmarks,
            min_width=self.min_width,
            max_width=self.max_width,
            max_aspect_ratio=self.max_aspect_ratio,
            roi=self.roi,
        ):
            return None

        bounds = get_squat_pose_bounds(landmarks)
        center = (bounds.center_x, bounds.center_y)
        if self._last_center is not None:
            dx = center[0] - self._last_center[0]
            dy = center[1] - self._last_center[1]
            distance = (dx * dx + dy * dy) ** 0.5
            if distance > self.max_center_jump:
                return None

        self._last_center = center
        return landmarks


# 관리자 기준 영상 전용 판별 완화값 (2026-10-21).
#
# 위 기본값은 «헬스장 랙·기구를 사람으로 잘못 잡는» 오검출을 막으려고 사용자 영상 기준으로 좁게 잡혀
# 있다(ROI 가 화면 가운데 · 하체 폭 0.1~0.34 · 폭/높이 ≤0.9). 관리자가 고른 기준 영상은 사정이 다르다:
#   - 가까이·세로로 찍으면 바닥 자세에서 하체 폭이 0.34·비율이 0.9 를 넘는다 → 상한을 0.6·1.3 으로.
#   - 측면에서 찍으면 선 자세의 하체 폭이 0.02 안팎이다(3D 마네킹 측면 영상 실측) → 하한 0.1 에 걸려
#     «선 자세» 를 한 장도 못 봐 반복 0회가 된다. 그래서 폭 하한을 없앤다(높이 하한 0.2 는 유지).
#   - ROI 는 걸지 않는다 — 사람이 화면 어디에 있든 영상 전체가 그 사람이다.
# 가시성 하한(0.65)은 그대로 둔다 — 관절이 안 보이는 프레임이 정답지에 섞이면 안 된다.
REFERENCE_MIN_WIDTH = 0.0
REFERENCE_MAX_WIDTH = 0.6
REFERENCE_MAX_ASPECT_RATIO = 1.3


def reference_pose_tracker() -> PoseTargetTracker:
    """관리자 기준 영상 추출용 트래커 — ROI 없음 · 폭/비율 완화 · 가시성 검사 유지."""
    return PoseTargetTracker(
        roi=None,
        min_width=REFERENCE_MIN_WIDTH,
        max_width=REFERENCE_MAX_WIDTH,
        max_aspect_ratio=REFERENCE_MAX_ASPECT_RATIO,
    )
