"""스쿼트를 골랐을 때 런지를, 런지를 골랐을 때 스쿼트를 세지 않는지 (2026-10-19).

시연 중 «런지 선택 → 스쿼트 동작» 과 «스쿼트 선택 → 런지 동작» 이 둘 다 횟수·점수로
잡히던 문제의 회귀 테스트다. 판정 근거는 app/core/stance.py 의 docstring 참고.

좌표는 합성이다 — 카메라 정확도를 재는 테스트가 아니라 «두 자세를 서로의 분석기가
거절하는가» 를 고정하는 테스트다. 실촬영 확인은 stance.py 의 측정 표가 담당한다.
"""
import math

from app.core.lunge_analyzer import StreamingLungeAnalyzer
from app.core.squat_analyzer import StreamingSquatAnalyzer
from app.core.squat_counter import SquatCounter, observe_squat
from app.core.stance import stance_features
from app.grpc.session_state import SessionState
from app.models.pose import Landmark

# 몸통(어깨↔골반) 0.25, 측면에서 오른쪽(+x)을 보고 선 사람.
_LUNGE_STAND = {
    11: (.43, .25), 12: (.43, .25), 23: (.43, .50), 24: (.43, .50),
    25: (.525, .70), 26: (.335, .70), 27: (.62, .90), 28: (.24, .90),
}
_LUNGE_BOTTOM = {
    11: (.42, .45), 12: (.42, .45), 23: (.42, .70), 24: (.42, .70),
    25: (.62, .70), 26: (.42, .88), 27: (.62, .90), 28: (.24, .90),
}
# 45°쯤에서 찍은 스쿼트처럼 두 발목이 가로로 벌어져 보이지만(간격/몸통 ≈ 0.96)
# 두 허벅지는 같은 방향·같은 각도로 굽는다.
_SQUAT_STAND = {
    11: (.47, .25), 12: (.53, .25), 23: (.47, .50), 24: (.53, .50),
    25: (.44, .70), 26: (.62, .70), 27: (.40, .90), 28: (.64, .90),
}
_SQUAT_BOTTOM = {
    11: (.50, .47), 12: (.56, .47), 23: (.38, .68), 24: (.44, .68),
    25: (.56, .70), 26: (.62, .70), 27: (.40, .90), 28: (.64, .90),
}


def _frame(stand, bottom, depth):
    points = {i: tuple(a + depth * (b - a) for a, b in zip(stand[i], bottom[i])) for i in stand}
    # 런지 분석기는 발꿈치·발끝(29~32)도 요구한다 — 발목 바로 옆에 둔다.
    for heel, toe, ankle in ((29, 31, 27), (30, 32, 28)):
        x, y = points[ankle]
        points[heel], points[toe] = (x - .02, y + .01), (x + .04, y + .01)
    landmarks = [Landmark(index=i, x=.5, y=.5, z=0, visibility=.05) for i in range(33)]
    for i, (x, y) in points.items():
        landmarks[i] = Landmark(index=i, x=x, y=y, z=0, visibility=.99)
    return landmarks


def _cycle(stand, bottom, fps=10):
    """서기 1초 → 내려가기 0.7초 → 바닥 0.5초 → 올라오기 0.7초 → 서기 1초, 2회."""
    out = []
    for _ in range(2):
        out += [0.0] * fps
        out += [k / (0.7 * fps) for k in range(1, int(0.7 * fps) + 1)]
        out += [1.0] * (fps // 2)
        out += [1 - k / (0.7 * fps) for k in range(1, int(0.7 * fps) + 1)]
    out += [0.0] * fps
    return [(i / fps, _frame(stand, bottom, d)) for i, d in enumerate(out, start=1)]


def _squat_reps(frames):
    counter = SquatCounter()
    for t, landmarks in frames:
        counter.update(landmarks, t)
    return counter.rep_count


def _lunge_reps(frames):
    state = SessionState(1, 2, exercise_type="lunge")
    analyzer = StreamingLungeAnalyzer()
    for t, landmarks in frames:
        analyzer.process_frame(state, landmarks, timestamp_sec=t)
    return state.rep_count


def test_synthetic_poses_have_the_intended_shape():
    lunge = observe_squat(_frame(_LUNGE_STAND, _LUNGE_BOTTOM, 1), 1.0, .55)
    squat = observe_squat(_frame(_SQUAT_STAND, _SQUAT_BOTTOM, 1), 1.0, .55)
    # 둘 다 «무릎이 충분히 굽었다» 는 조건은 만족한다 — 그래서 예전에는 서로 세졌다.
    assert max(lunge.knees) <= 115 and max(squat.knees) <= 115
    assert lunge.stance.is_split()
    assert squat.stance.stance_ratio >= 0.55  # 기존 런지 구분 문턱(stagger_min)은 넘는다
    assert not squat.stance.is_split()


def test_each_analyzer_still_counts_its_own_exercise():
    assert _lunge_reps(_cycle(_LUNGE_STAND, _LUNGE_BOTTOM)) == 2
    assert _squat_reps(_cycle(_SQUAT_STAND, _SQUAT_BOTTOM)) == 2


def test_squat_selected_lunge_performed_is_not_counted():
    counter = SquatCounter()
    messages = set()
    for t, landmarks in _cycle(_LUNGE_STAND, _LUNGE_BOTTOM):
        counter.update(landmarks, t)
        if counter.coaching_event:
            messages.add(counter.coaching_event)
    assert counter.rep_count == 0
    assert any("런지" in m for m in messages)


def test_lunge_selected_squat_performed_is_not_counted():
    state = SessionState(1, 2, exercise_type="lunge")
    analyzer = StreamingLungeAnalyzer()
    guidance = set()
    for t, landmarks in _cycle(_SQUAT_STAND, _SQUAT_BOTTOM):
        analyzer.process_frame(state, landmarks, timestamp_sec=t)
        guidance.add(state.lunge_state.guidance)
    assert state.rep_count == 0
    assert any("스쿼트 자세" in g for g in guidance)


def test_streaming_squat_session_ignores_lunges():
    """실시간 경로(pose.py 가 부르는 StreamingSquatAnalyzer)도 같은 판정을 탄다."""
    state = SessionState(1, 1, exercise_type="squat")
    analyzer = StreamingSquatAnalyzer("squat")
    events = []
    for t, landmarks in _cycle(_LUNGE_STAND, _LUNGE_BOTTOM):
        _, _, event, _, _ = analyzer.process_frame(state, landmarks, timestamp_sec=t)
        if event:
            events.append(event)
    assert events == [] and state.rep_count == 0


def test_one_visible_leg_is_not_treated_as_lunge():
    """측면 스쿼트처럼 한쪽 다리만 보이면 판정 불가(None) — 스쿼트를 잘못 막지 않는다."""
    landmarks = _frame(_SQUAT_STAND, _SQUAT_BOTTOM, 1)
    for i in (12, 24, 26, 28):
        landmarks[i] = Landmark(index=i, x=landmarks[i].x, y=landmarks[i].y, z=0, visibility=.1)
    assert observe_squat(landmarks, 1.0, .55).stance is None
    assert stance_features({11: (0, 0)}) is None
    assert math.isfinite(observe_squat(landmarks, 1.0, .55).knee_angle)
