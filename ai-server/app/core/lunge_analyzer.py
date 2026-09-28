"""Side-view lunge counter. See docs/decisions/lunge-repetition-counting.md.

100 degree bottom boundary follows Sousa et al. (2026). Timing and visibility
gates are engineering defaults requiring video validation. All mutable data
belongs to a session; this analyzer is safe to share.

2026-09-22 재설계 (2단계):

1단계 — 판정 축을 발 이동 거리에서 무릎 각도 사이클로 바꿨다. 예전에는 "두 발 모으고
서 있다가 한쪽 발이 정해진 거리만큼 앞으로 이동"하는 것으로 1회를 판정했는데, 실촬영
4개(정상·얕음·워킹·45도)를 검증해보니 전부 "이미 벌어진 스탠스에서 제자리로 무릎만
굽혔다 폈다" 하는 동작이라 발 이동 문턱(0.65)을 못 넘어 전부 0회였다. 스쿼트
(squat_counter.py)처럼 스탠스가 벌어져 있고 한쪽 무릎이 충분히 굽혀졌다 펴지면 1회로
바꿨다 — 발이 원위치로 돌아오는지는 더 이상 보지 않는다.

2단계 — "가만히 서 있는 준비 자세"를 기다리는 calibration 단계를 없앴다. 1단계 수정
후에도, 실촬영에서 반복 사이 최고점이 무릎을 완전히 펴는 160도까지 못 미치고(130~170도
사이로 들쭉날쭉) 다음 반복으로 넘어가는 경우가 많아, "완전히 일어서야 다음 반복을 받을
준비가 된다"는 전제 자체가 반복 여러 개를 하나로 뭉개버렸다(lunge_normal_01에서 8초간
5개 가까운 펄스가 1회로 합쳐짐). "런지가 보이면 바로 센다"는 사용자 확인에 따라, 준비
단계 자체를 없애고 바닥(bottom_angle)에 닿았다가 어느 정도(recovered_angle) 다시
펴지면 그 즉시 다음 반복을 받는다 — 완전한 직립을 요구하지 않는다.

트레이드오프(사용자에게 명시적으로 확인함):
- 전방/후방/워킹 런지를 구분하지 못한다 — 발 이동 방향을 추적하지 않기 때문이다. 대신
  스쿼트(양발 나란히)와는 스탠스 벌어짐 정도(stagger_min)로 구분한다.
- "준비 자세 확인"이 없으므로 영상·스트림이 이미 런지 바닥 자세로 시작해도 그 뒤로
  다시 펴는 동작을 1회로 센다 — 시작 시점을 놓쳤다는 이유로 거부하지 않는다.
- recovered_angle(130)이 standing(160)보다 낮아, 무릎을 끝까지 펴지 않고 반복하는
  얕은 펄스가 실제보다 여러 회로 쪼개질 여지가 있다. 반대로 너무 낮추면 반복 하나를
  둘로 오카운트하므로, 이 값은 2026-09-22 실촬영 관찰(반복 사이 최고점이 130도 밑으로는
  거의 안 내려감)에 맞춘 구현값이다 — 논문 검증치가 아니다.

바닥 검출(반복이 셌는가)과 좌우 라벨(left_reps/right_reps 어느 쪽에 더할지)은 분리돼
있다 — 바닥은 두 무릎 중 더 굽은 쪽으로 보고, 라벨은 발목-골반중심 거리로 따로 정한다.
**라벨 쪽은 완전 측면(옆) 촬영에서만 검증됐다.** 2026-09-22 실촬영 4개 중 완전 측면
3개(정상·얕음·워킹)는 좌우 라벨이 실제 동작과 맞았지만, 45도 각도로 찍은 1개는 총
횟수는 맞아도 좌우 라벨의 9/10이 틀렸다(전부 오른쪽 동작인 구간을 대부분 "왼쪽"으로
잘못 붙임) — 발목-골반중심 거리 신호가 완전 측면이 아닌 각도에서는 신뢰할 수 없다는
뜻이다. 그래서 **런지는 완전 측면 촬영만 지원 범위로 두고, 45도 등 비스듬한 각도는
지원 범위 밖으로 명시한다** — 총 횟수는 맞을 수 있어도 좌우 구분은 보장하지 않는다.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from app.models.pose import Landmark, LungeProgress

if TYPE_CHECKING:
    from app.grpc.session_state import SessionState


@dataclass(frozen=True)
class LungeThresholds:
    bottom_angle: float = 100.0
    # 바닥에서 이만큼 다시 펴지면 다음 반복을 받을 준비가 된 것으로 본다. 완전히 편 자세
    # (standing, 160도)까지 기다리지 않는다 — 위 모듈 docstring 2단계 참고.
    recovered_angle: float = 130.0
    visibility: float = 0.6
    bottom_confirm_sec: float = 0.12
    recovery_confirm_sec: float = 0.12
    min_rep_sec: float = 0.6
    max_gap_sec: float = 1.2
    smoothing_sec: float = 0.06
    # 두 발목 사이 거리 / 어깨-골반 길이. 이 값으로 "런지 스탠스(다리가 앞뒤로 벌어짐)"와
    # "스쿼트 스탠스(발이 나란함)"를 가른다 — 발 이동 여부는 더 이상 보지 않으므로 이 값이
    # 사실상 유일한 구분 신호다.
    #
    # 2026-09-22 실촬영 4개(lunge_normal_01·45degree_01·sallow_01·walking_01) 검증: 무릎이
    # 실제로 굽혀진 순간의 비율이 전부 0.74~1.70이었고, 가만히 선 자세는 0~0.45였다. 그
    # 사이인 0.55를 문턱으로 쓴다. 스쿼트(발이 나란한) 실촬영으로는 아직 재검증하지 않았다
    # — 발이 나란해도 몸통이 기울면 이 비율이 올라갈 수 있어, 오탐 여지가 남아 있다.
    stagger_min: float = 0.55


@dataclass
class LungeState:
    # "calibrating"은 더 이상 만들어지지 않는다 — 응답 스키마(LungeProgress.phase)와의
    # 호환을 위해 값 자체만 남겨둔다. 세션은 항상 "ready"에서 시작한다.
    phase: str = "ready"
    lead_leg: str | None = None
    left_reps: int = 0
    right_reps: int = 0
    unassigned_reps: int = 0
    last_time: float | None = None
    bottom_since: float | None = None
    return_since: float | None = None
    started_at: float | None = None
    knees: list[float] = field(default_factory=list)
    guidance: str = "완전한 측면에서 런지 자세가 보이면 바로 인식합니다."

    def invalidate(self, message: str) -> None:
        self.phase = "ready"
        self.lead_leg = None
        self.bottom_since = self.return_since = None
        self.started_at = None
        self.knees.clear()
        self.guidance = message

    def progress(self) -> LungeProgress:
        return LungeProgress(
            phase=self.phase, lead_leg=self.lead_leg, left_reps=self.left_reps,
            right_reps=self.right_reps, unassigned_reps=self.unassigned_reps,
            guidance=self.guidance,
        )


@dataclass(frozen=True)
class LungeRepEvent:
    rep_number: int
    lead_leg: str
    # Existing protobuf requires a number. Spring treats lunge scores as unmeasured.
    sync_rate: float = 0.0
    feedback_message: str = ""


def _angle(a, b, c) -> float:
    u, v = (a[0] - b[0], a[1] - b[1]), (c[0] - b[0], c[1] - b[1])
    norm = math.hypot(*u) * math.hypot(*v)
    if norm < 1e-8:
        raise ValueError("degenerate joint")
    cosine = max(-1.0, min(1.0, (u[0] * v[0] + u[1] * v[1]) / norm))
    return math.degrees(math.acos(cosine))


def lunge_geometry(landmarks: list[Landmark], aspect_ratio: float = 1.0):
    """Image-plane angles: correct normalized x for width/height, ignore noisy z."""
    required = (11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32)
    lm = {p.index: p for p in landmarks}
    if not math.isfinite(aspect_ratio) or aspect_ratio <= 0:
        raise ValueError("invalid image shape")
    if any(i not in lm or not all(math.isfinite(v) for v in
           (lm[i].x, lm[i].y, lm[i].visibility)) for i in required):
        raise ValueError("missing/nonfinite landmark")
    points = {i: (lm[i].x * aspect_ratio, lm[i].y) for i in required}
    angles = [_angle(points[h], points[k], points[a])
              for h, k, a in ((23, 25, 27), (24, 26, 28))]
    return lm, points, angles


class StreamingLungeAnalyzer:
    def __init__(self, thresholds: LungeThresholds | None = None):
        self.thresholds = thresholds or LungeThresholds()

    def process_frame(self, state: SessionState, landmarks: list[Landmark], *,
                      timestamp_sec: float, image_aspect_ratio: float = 1.0):
        if state.lunge_state is None:
            state.lunge_state = LungeState(unassigned_reps=state.rep_count)
        s, t = state.lunge_state, self.thresholds
        now = timestamp_sec
        if not math.isfinite(now) or (s.last_time is not None and now <= s.last_time):
            return None, None, None
        dt = now - s.last_time if s.last_time is not None else 0.0
        s.last_time = now
        if dt > t.max_gap_sec:
            s.invalidate("영상이 끊겼습니다. 다시 런지를 시작해주세요.")
            state.current_rep_frames.clear()
        try:
            lm, p, angles = lunge_geometry(landmarks, image_aspect_ratio)
            if any(lm[i].visibility < t.visibility for i in p):
                raise ValueError("low visibility")
        except ValueError:
            s.invalidate("양쪽 무릎과 발끝이 모두 보이도록 위치를 조정해주세요.")
            state.current_rep_frames.clear()
            return None, None, None

        alpha = 1.0 - math.exp(-dt / t.smoothing_sec) if dt else 1.0
        s.knees = [old + alpha * (new - old) for old, new in zip(s.knees, angles)] \
            if s.knees else angles.copy()
        mean_knee = sum(s.knees) / 2

        ankles = [p[27], p[28]]
        shoulder = ((p[11][0] + p[12][0]) / 2, (p[11][1] + p[12][1]) / 2)
        hip = ((p[23][0] + p[24][0]) / 2, (p[23][1] + p[24][1]) / 2)
        scale = math.dist(shoulder, hip)
        stagger = abs(ankles[0][0] - ankles[1][0]) / scale if scale > 1e-6 else 0.0
        lunge_stance = stagger >= t.stagger_min

        if s.phase == "ready":
            if not lunge_stance:
                s.bottom_since = None
                return angles, mean_knee, None
            # "바닥에 닿았는가"는 두 무릎 중 더 굽은 쪽으로 판단한다 — 어느 다리를 lead로
            # 붙일지와는 분리한다. 워킹 런지 실측(lunge_walking_01, 2026-09-22)에서 두
            # 신호가 다른 다리를 가리키는 프레임이 실제로 나왔다: 발목이 골반 중심에서
            # 더 멀리 떨어진 다리(=앞다리로 보이는 쪽)보다 반대쪽 무릎이 더 깊이 굽은
            # 순간이 있었다(몸 전체가 전진하는 워킹 런지 특성상 뒷다리가 아직 이전
            # 자세에서 덜 펴진 채로 남아 있을 수 있다). lead 신호 하나로 바닥 검출까지
            # 묶으면 이런 프레임에서 진짜 반복을 통째로 놓친다.
            if min(s.knees) > t.bottom_angle:
                s.bottom_since = None
                return angles, mean_knee, None
            if s.bottom_since is None:
                s.bottom_since = now
            # 표본 간격(dt)이 이미 확인창(bottom_confirm_sec)보다 크면, 이번 표본 하나가
            # 이 입력이 보여줄 수 있는 가장 촘촘한 체류 증거다 — 3fps(≈0.33초 간격)에서
            # 0.12초짜리 확인창을 "표본 2개"로 요구하면 한 표본만 바닥을 스친 진짜 반복이
            # 전부 씹힌다(2026-09-22 lunge_sallow_01 실측: 10.23초 등 여러 지점에서 정확히
            # 이 이유로 반복이 누락됐다). squat_counter.py의 standing_confirm 판정과 같은
            # 보정이다.
            if now - s.bottom_since < t.bottom_confirm_sec and dt < t.bottom_confirm_sec:
                return angles, mean_knee, None
            # lead(왼쪽/오른쪽 집계용 라벨)는 발목-골반중심 거리로 따로 정한다 — 바닥
            # 검출과 분리했으므로 여기서 잘못 골라도 반복 자체는 이미 세어진 뒤다.
            hip_center_x = (p[23][0] + p[24][0]) / 2
            offsets = [abs(a[0] - hip_center_x) for a in ankles]
            lead = 0 if offsets[0] >= offsets[1] else 1
            s.lead_leg = "left" if lead == 0 else "right"
            s.started_at = s.bottom_since
            s.phase = "returning"
            s.bottom_since = None
            s.guidance = "다시 일어서주세요."
            state.current_rep_frames.clear()
            return angles, mean_knee, None

        # s.phase == "returning" — 이 단계에서 반환하는 깊이 지표는 두 무릎 평균이 아니라
        # **lead 다리의 무릎각 하나**다(2026-09-27). pose.py가 이 값을 그대로
        # PerRepFrame.smoothed_knee_angle에 실어 Spring에 보내고, 백엔드의 회차별 깊이 채점
        # (LungeDepthAnalysisCalculator)은 그 값의 rep 내 최솟값으로 "이 회차가 얼마나
        # 깊었는지"를 판정한다 — 평균을 쓰면 반대쪽(뒷)다리가 거의 안 굽는 런지 특성상 실제
        # 깊이보다 항상 얕게 보인다. current_rep_frames는 바닥이 확정되는 순간(위에서) 비워지고
        # 이 반환값으로 다시 채워지므로, 여기서부터가 실제로 Spring에 전송되는 구간이다 —
        # 회복 판정(max, 어느 쪽이든 펴지면 됨)과 깊이 보고(lead 쪽만) 기준이 다른 것은 의도적.
        lead = 0 if s.lead_leg == "left" else 1
        lead_knee = s.knees[lead]
        if max(s.knees) < t.recovered_angle:
            s.return_since = None
            return angles, lead_knee, None
        if s.return_since is None:
            s.return_since = now
        if now - s.return_since < t.recovery_confirm_sec and dt < t.recovery_confirm_sec:
            return angles, lead_knee, None
        event = None
        if now - s.started_at >= t.min_rep_sec:
            state.rep_count += 1
            if s.lead_leg == "left":
                s.left_reps += 1
            else:
                s.right_reps += 1
            event = LungeRepEvent(state.rep_count, s.lead_leg)
        s.phase, s.lead_leg = "ready", None
        s.bottom_since = s.return_since = None
        s.guidance = "다시 일어선 것을 확인했습니다. 다음 런지를 시작하세요."
        return angles, lead_knee, event
