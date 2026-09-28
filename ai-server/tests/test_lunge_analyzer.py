"""Deterministic trajectory tests; these do not measure camera accuracy.

2026-09-22: 판정 축을 발 이동 거리에서 무릎 각도 사이클(+ 스탠스 벌어짐)로 바꿨다. 그래서
예전에는 "발이 원위치로 안 돌아온다"는 이유로 거부하던 워킹·후방 런지 스타일도 이제는
스쿼트와만 구분되면(스탠스가 벌어져 있으면) 센다 — 실촬영 검증에서 실제로 촬영된 동작
(제자리에서 벌린 다리로 펄싱)이 이 방식이었기 때문이다(사용자 확인 후 반영).
"""
import math
from dataclasses import replace

import pytest

from app.core.lunge_analyzer import StreamingLungeAnalyzer
from app.grpc.session_state import SessionState
from app.models.pose import Landmark


def frame(left=175, right=175, left_step=0.0, right_step=0.0, *, mirror=False, aspect=1.0):
    points = {11: (.48, .23), 12: (.50, .23), 23: (.48, .48), 24: (.50, .48)}
    for hip, knee, ankle, heel, toe, angle, x in (
        (23, 25, 27, 29, 31, left, .46 + left_step),
        (24, 26, 28, 30, 32, right, .51 + right_step),
    ):
        a, c = points[hip], (x, .9)
        dx, dy = c[0] - a[0], c[1] - a[1]
        offset = 1 / (2 * math.tan(math.radians(angle / 2)))
        points[knee] = ((a[0] + c[0]) / 2 + dy * offset,
                        (a[1] + c[1]) / 2 - dx * offset)
        points[ankle], points[heel], points[toe] = c, (x - .025, .91), (x + .04, .91)
    return [Landmark(index=i, x=(1 - x if mirror else x) / aspect,
                     y=y, z=0, visibility=1) for i, (x, y) in points.items()]


class Run:
    def __init__(self, fps=10, initial=0, **geometry):
        self.state = SessionState(123, 2, exercise_type="lunge", rep_count=initial)
        self.analyzer = StreamingLungeAnalyzer()
        self.now, self.dt, self.geometry = 0., 1 / fps, geometry
        self.events = []

    def hold(self, seconds, *args, **kwargs):
        for _ in range(math.ceil(seconds / self.dt)):
            self.now += self.dt
            _, _, event, _, _ = self.analyzer.process_frame(
                self.state, frame(*args, **kwargs, **self.geometry), timestamp_sec=self.now,
                image_aspect_ratio=self.geometry.get("aspect", 1.),
            )
            if event:
                self.events.append(event)

    def rep(self, leg="left", bottom=90, back_bottom=85):
        step = {f"{leg}_step": .3}
        self.hold(.7, 150, 150, **step)
        angles = (bottom, back_bottom) if leg == "left" else (back_bottom, bottom)
        self.hold(.7, *angles, **step)
        before = self.state.rep_count
        # 무릎만 다시 펴면 완성된다 — 발이 원위치로 돌아왔는지는 더 이상 보지 않는다.
        self.hold(.7, 175, 175, **step)
        assert self.state.rep_count == len(self.events) + self.state.lunge_state.unassigned_reps
        return self.state.rep_count - before


@pytest.mark.parametrize("fps", [3, 10, 30])
@pytest.mark.parametrize("mirror,aspect", [(False, 1.), (True, 1.), (False, 16/9), (True, 9/16)])
def test_alternating_forward_lunges(fps, mirror, aspect):
    run = Run(fps, mirror=mirror, aspect=aspect)
    run.hold(1)
    assert run.rep("left") == 1
    assert run.rep("right") == 1
    assert run.state.rep_count == 2
    assert [e.lead_leg for e in run.events] == ["left", "right"]
    progress = run.state.lunge_state.progress()
    assert (progress.left_reps, progress.right_reps) == (1, 1)


@pytest.mark.parametrize("kind", ["squat", "walking", "shallow", "partial"])
def test_non_lunge_cycles_are_not_counted(kind):
    """스탠스가 벌어지지 않거나(스쿼트·평행 이동) 깊이가 부족하면 세지 않는다."""
    run = Run()
    run.hold(1)
    if kind == "squat":
        run.hold(1, 90, 90)  # 발이 나란함 — 스탠스 벌어짐 신호가 없다
    elif kind == "walking":
        run.hold(.6, left_step=.3)
        # 두 발이 같이 움직이면 서로 간 거리(stagger)는 그대로다 — 런지 스탠스가 아니다.
        run.hold(1, 90, 90, left_step=.3, right_step=.3)
    elif kind == "shallow":
        run.rep(bottom=125, back_bottom=125)
    elif kind == "partial":
        run.hold(1, 150, 150, left_step=.3)
    run.hold(1)
    assert run.state.rep_count == 0


@pytest.mark.parametrize("kind", ["reverse", "split_stance"])
def test_stance_based_cycles_now_count(kind):
    """2026-09-22 재설계: 발 이동 방향·원위치 복귀를 더 이상 보지 않으므로, 스탠스가
    벌어진 채(방향 무관) 무릎이 굽혔다 펴지면 워킹·후방 런지 스타일도 센다 — 실촬영에서
    실제로 촬영된 "제자리 벌린 스탠스로 펄싱"하는 동작이 이 모양이었다(사용자 확인 후
    반영, docs/decisions/lunge-repetition-counting.md 참고).
    """
    run = Run()
    if kind == "split_stance":
        run.hold(1, left_step=.3)
        run.hold(1, 90, 85, left_step=.3)
        run.hold(1, left_step=.3)
    elif kind == "reverse":
        run.hold(1)
        run.hold(1, 90, 90, right_step=-.3)
        run.hold(1)
    assert run.state.rep_count == 1


@pytest.mark.parametrize("failure", ["gap", "visibility", "missing", "nan"])
def test_interrupted_rep_is_discarded_and_next_rep_works(failure):
    run = Run()
    run.hold(1)
    run.hold(.7, 145, 145, left_step=.3)
    run.hold(.7, 90, 85, left_step=.3)
    if failure == "gap":
        run.now += 3
    else:
        bad = frame(90, 85, left_step=.3)
        if failure == "visibility":
            bad[0].visibility = .1
        elif failure == "missing":
            bad = bad[:-1]
        else:
            bad[0].x = float("nan")
        run.now += .1
        assert run.analyzer.process_frame(run.state, bad, timestamp_sec=run.now)[0] is None
    run.hold(1)
    assert run.state.rep_count == 0
    assert run.rep() == 1


def test_process_frame_returns_torso_tilt():
    """체간 기울기 점수(TrunkLeanAnalysisCalculator, 2026-09-28)용 4번째 반환값 — squat과
    같은 공식으로 어깨/엉덩이 중점 벡터의 수직 이탈각을 낸다. frame()의 어깨(11/12)·엉덩이
    (23/24)는 항상 같은 x좌표라 거의 직립(0도 근처)이어야 한다."""
    run = Run()
    run.hold(.3)
    _, _, _, torso_tilt, _ = run.analyzer.process_frame(
        run.state, frame(150, 150, left_step=.3), timestamp_sec=run.now + .1
    )
    assert torso_tilt is not None
    assert torso_tilt < 5.0


def test_process_frame_returns_stagger_ratio():
    """보폭 점수(LungeStepLengthAnalysisCalculator, 2026-09-28)용 5번째 반환값 — "런지
    자세인가"를 판정하던 stagger 값을 그대로 재사용한다. left_step=.3인 frame()은 두 발목이
    벌어져 있으므로 stagger_ratio가 stagger_min(0.55)보다 뚜렷이 커야 한다."""
    run = Run()
    run.hold(.3)
    _, _, _, _, stagger_ratio = run.analyzer.process_frame(
        run.state, frame(150, 150, left_step=.3), timestamp_sec=run.now + .1
    )
    assert stagger_ratio is not None
    assert stagger_ratio > 0.55


@pytest.mark.parametrize("failure", ["visibility", "missing", "nan"])
def test_invalid_frame_returns_none_extras_too(failure):
    """가시성 부족·랜드마크 누락·NaN — 무릎각(angles)이 None이 되는 경로는 체간 기울기·보폭
    비율도 같이 None이어야 한다(pose.py가 angles is None일 때 PerRepFrame을 아예 안 만드므로,
    이 값들이 항상 같이 유효/무효여야 한다)."""
    run = Run()
    bad = frame(150, 150, left_step=.3)
    if failure == "visibility":
        bad[0].visibility = .1
    elif failure == "missing":
        bad = bad[:-1]
    else:
        bad[0].x = float("nan")
    angles, _, _, torso_tilt, stagger_ratio = run.analyzer.process_frame(
        run.state, bad, timestamp_sec=run.now + .1
    )
    assert angles is None
    assert torso_tilt is None
    assert stagger_ratio is None


def test_resume_counts_total_without_inventing_historical_sides():
    run = Run(initial=7)
    run.hold(1)
    run.rep("right")
    assert run.state.rep_count == 8
    assert run.state.lunge_state.progress().model_dump() | {} == {
        "phase": "ready", "lead_leg": None, "left_reps": 0, "right_reps": 1,
        "unassigned_reps": 7, "score_available": False,
        "guidance": "다시 일어선 것을 확인했습니다. 다음 런지를 시작하세요.",
    }


def test_starting_already_at_bottom_still_counts_the_recovery():
    """2026-09-22 재설계: 준비 자세(calibration)를 더 이상 기다리지 않는다 — 스트림이
    이미 런지 바닥 자세로 시작해도, 그 뒤로 다시 펴면 1회로 센다. "시작하는 순간을
    놓쳤다"는 이유로 거부하지 않는 것이 이번 재설계의 요점이다."""
    run = Run()
    run.hold(1, 90, 85, left_step=.3)
    run.hold(1)
    assert run.state.rep_count == 1


def test_out_of_order_frame_does_not_change_state():
    run = Run()
    run.hold(1, 90, 85, left_step=.3)
    before = replace(run.state.lunge_state)
    run.analyzer.process_frame(run.state, frame(90, 85, left_step=.3), timestamp_sec=run.now - 1)
    assert run.state.lunge_state == before


def test_long_bottom_hold_is_one_rep_and_standing_does_not_repeat():
    run = Run()
    run.hold(1)
    run.hold(.7, 145, 145, left_step=.3)
    run.hold(20, 90, 85, left_step=.3)
    run.hold(10)
    assert run.state.rep_count == 1


def test_low_fps_can_skip_intermediate_descent_frames():
    run = Run(fps=3)
    run.hold(1)
    run.hold(1, 90, 85, left_step=.3)
    run.hold(1)
    assert run.state.rep_count == 1


def test_single_bad_depth_frame_does_not_count():
    run = Run()
    run.hold(1)
    run.hold(.7, 140, 140, left_step=.3)
    run.hold(.1, 70, 70, left_step=.3)
    run.hold(.5, 140, 140, left_step=.3)
    run.hold(1)
    assert run.state.rep_count == 0
