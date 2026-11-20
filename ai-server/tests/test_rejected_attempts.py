"""스쿼트 «미인정 시도» — 분석기 요약 · 실시간 응답 · Spring 전송 · 재부착 (2026-10-21).

결정(docs/decisions/counted-reps-vs-scored-attempts.md §6): 횟수는 원래 규칙 그대로 두고, 내려갔다 올라왔지만
인정되지 않은 시도는 «미인정» 으로 화면에 보여주고 점수와 함께 리포트에 남긴다. 횟수·세트·목표는 인정 반복만 센다.

무엇을 고정하나:
    1. 분석기가 미인정 시도를 인정 반복과 같은 식으로 채점하고(0~100, 깊은 반복보다 낮다) 가장 깊은 순간을 고른다.
    2. 미인정은 rep_count·pose_data 배치·completed_reps 를 건드리지 않는다.
    3. POST /pose 응답에 rejected_count(늘)·rejected_reason(그 프레임만)이 실리고, 시도마다 Spring 전송이 한 번
       세션 락 밖에서 나간다.
    4. 재부착은 initial_rejected_count 에서 잇는다.
    5. spring_client.send_rejected_attempt 는 pose 배치와 같은 재시도 정책을 쓰고, 구버전 Spring(UNIMPLEMENTED)에도
       던지지 않는다.

어느 동작이 미인정인가(판정 자체)는 test_squat_counter.py 가 고정한다.
"""

from __future__ import annotations

import json
import logging
from unittest import mock
from unittest.mock import Mock

import grpc
import pytest

import exercise_pb2
from app.api.endpoints import pose as pose_endpoint
from app.api.endpoints.pose import _landmarks_to_json
from app.config import settings
from app.core import squat_analyzer
from app.core.angle_calculator import extract_angles
from app.core.squat_analyzer import RejectedAttemptEvent, StreamingSquatAnalyzer
from app.grpc import exercise_servicer, spring_client
from app.grpc.session_state import PerRepFrame, SessionState, SessionStateRegistry, get_registry
from app.models.pose import PoseRequest
from tests.test_exercise_separation import _LUNGE_BOTTOM, _LUNGE_STAND, _cycle
from tests.test_pose_response_contract import _BLANK_IMAGE, _fake_lease
from tests.test_spring_client_deadline import _FakeRpcError
from tests.test_squat_analyzer import _frame

# 3fps 기준. 얕은 시도(무릎 120°)는 골반은 충분히 내려가지만 바닥 조건(≤115°)에 못 닿아 «깊이 미확인» 미인정 1회다.
_SHALLOW = [175.0] * 3 + [120.0] * 4 + [175.0] * 3
_DEEP = [175.0] * 3 + [140.0, 95.0, 85.0, 85.0, 95.0, 140.0] + [175.0] * 4
# 기준 궤적 — 깊은 스쿼트 한 번(175° → 85° → 175°)
_REFERENCE = [extract_angles(_frame(k), "squat") for k in (175, 150, 120, 95, 85, 95, 120, 150, 175)]


def _drive(state, knees):
    """pose.py 의 호출 순서를 흉내 낸다 — 판정 → 버퍼에 넣기 → rep 이면 비우기. 미인정 요약과 rep 이벤트를 모은다.

    knees 의 원소는 무릎각이거나 (무릎각, 체간 기울기) 쌍이다.
    """
    analyzer = StreamingSquatAnalyzer("squat")
    rejected, reps = [], []
    for i, item in enumerate(knees):
        knee, tilt = item if isinstance(item, tuple) else (item, 5.0)
        angles, smoothed, rep_event, torso_tilt, _ = analyzer.process_frame(
            state, _frame(knee, tilt), timestamp_sec=i / 3)
        if state.pending_rejected_attempt is not None:
            rejected.append(state.pending_rejected_attempt)
        if angles is not None:
            state.current_rep_frames.append(PerRepFrame(
                timestamp_sec=i / 3, joint_coordinates=f'{{"i": {i}}}', angles=angles,
                smoothed_knee_angle=smoothed, torso_tilt_deg=torso_tilt))
        if rep_event is not None:
            reps.append(rep_event)
            state.current_rep_frames.clear()
    return rejected, reps


# ─── 1·2. 분석기 요약 ─────────────────────────────────────────────────────────


def test_미인정_시도는_인정_반복과_같은_식으로_채점되고_깊은_반복보다_낮다():
    state = SessionState(session_id=1, exercise_id=1, reference_angles=_REFERENCE)
    rejected, reps = _drive(state, _SHALLOW + _DEEP)

    assert len(rejected) == 1 and len(reps) == 1
    event = rejected[0]
    assert isinstance(event, RejectedAttemptEvent)
    assert (event.attempt_number, event.reason) == (1, "DEPTH_NOT_CONFIRMED")
    assert 0.0 <= event.sync_rate <= 100.0
    assert event.sync_rate < reps[0].sync_rate, (event.sync_rate, reps[0].sync_rate)
    # 미인정은 횟수에 안 들어간다 — 뒤의 인정 반복이 1번이다
    assert reps[0].rep_number == 1
    assert state.rep_count == 1
    assert state.rejected_count == 1


def test_미인정_채점은_시도_프레임과_끝_프레임을_시각과_함께_넘긴다(monkeypatch):
    seen = []

    def fake(ref, user, user_times=None):
        seen.append((len(user), user_times))
        return 42.0

    monkeypatch.setattr(squat_analyzer, "compute_sync_rate", fake)
    state = SessionState(session_id=1, exercise_id=1, reference_angles=_REFERENCE)
    rejected, _ = _drive(state, _SHALLOW)

    [event] = rejected
    assert event.sync_rate == 42.0
    [(count, times)] = seen
    # 시도 프레임 4장(내려간 120° ×4 — 버퍼를 비우기 전에 뜬 스냅샷) + 끝(다시 선) 프레임 1장. 그 앞의 선 자세는 없다.
    assert count == 5
    assert times == pytest.approx([3 / 3, 4 / 3, 5 / 3, 6 / 3, 7 / 3])


def test_요약은_가장_깊이_앉은_프레임에서_나온다():
    # 무릎 112° 표본 하나만 바닥 조건에 닿고 다음 표본에서 벗어난다 — 바닥 미확정, 그 표본이 가장 깊다.
    # 체간을 그 표본에서만 20° 로 둬서 «최저점 프레임의 값» 을 그대로 가져오는지 본다.
    knees = [175.0] * 3 + [130.0, (112.0, 20.0), 118.0, 125.0] + [175.0] * 3
    state = SessionState(session_id=1, exercise_id=1, reference_angles=_REFERENCE)
    rejected, reps = _drive(state, knees)

    assert reps == []
    [event] = rejected
    deepest = event.deepest_frame
    assert deepest is not None
    assert deepest.timestamp_sec == pytest.approx(4 / 3)
    assert deepest.joint_coordinates == '{"i": 4}'
    assert deepest.torso_tilt_deg == pytest.approx(20.0, abs=0.01)
    assert deepest.smoothed_knee_angle < 116


def test_런지_자세_미인정도_요약되고_번호가_이어지며_버퍼를_비운다():
    state = SessionState(session_id=1, exercise_id=1, reference_angles=_REFERENCE)
    analyzer = StreamingSquatAnalyzer("squat")
    events = []
    for t, landmarks in _cycle(_LUNGE_STAND, _LUNGE_BOTTOM):
        angles, smoothed, rep_event, tilt, _ = analyzer.process_frame(state, landmarks, timestamp_sec=t)
        assert rep_event is None
        if state.pending_rejected_attempt is not None:
            events.append(state.pending_rejected_attempt)
            # 바닥 → 준비(런지 자세로 버림)에서도 버퍼를 비운다 — 다음 시도에 섞이지 않는다
            assert len(state.current_rep_frames) == 0
        if angles is not None:
            state.current_rep_frames.append(PerRepFrame(
                timestamp_sec=t, joint_coordinates="{}", angles=angles,
                smoothed_knee_angle=smoothed, torso_tilt_deg=tilt))

    assert [(e.attempt_number, e.reason) for e in events] == [(1, "SPLIT_STANCE"), (2, "SPLIT_STANCE")]
    assert all(0.0 <= e.sync_rate <= 100.0 for e in events)
    assert all(e.deepest_frame is not None and e.deepest_frame.smoothed_knee_angle <= 115 for e in events)
    assert state.rep_count == 0
    assert state.rejected_count == 2


def test_재부착_상태의_미인정_수에서_번호를_잇는다():
    state = SessionState(session_id=1, exercise_id=1, reference_angles=_REFERENCE, rep_count=7, rejected_count=3)
    rejected, _ = _drive(state, _SHALLOW)

    assert [e.attempt_number for e in rejected] == [4]
    assert state.rejected_count == 4
    assert state.squat_counter.rejected_count == 4
    assert state.rep_count == 7


def test_미인정_요약은_그_프레임에만_실린다():
    state = SessionState(session_id=1, exercise_id=1, reference_angles=_REFERENCE)
    analyzer = StreamingSquatAnalyzer("squat")
    flags = []
    for i, knee in enumerate(_SHALLOW):
        analyzer.process_frame(state, _frame(knee), timestamp_sec=i / 3)
        flags.append(state.pending_rejected_attempt is not None)
    # 7번 프레임(다시 섬)에서만 — 호출부가 안 꺼내 가도 다음 프레임에서 지워진다
    assert flags == [False] * 7 + [True, False, False]


# ─── 3. POST /pose ────────────────────────────────────────────────────────────


def _run_pose(session_id, knees, *, on_rejected=None):
    frames = iter(_frame(k) for k in knees)
    clock = {"now": 0.0}
    req = PoseRequest(image="", session_id=session_id, exercise_type="squat")
    with mock.patch.object(pose_endpoint, "base64_to_image", lambda _: _BLANK_IMAGE), \
            mock.patch.object(pose_endpoint, "lease_detector", _fake_lease(lambda _img: next(frames))), \
            mock.patch.object(pose_endpoint, "accept_frame", lambda _s, _n: True), \
            mock.patch.object(pose_endpoint.time, "monotonic", lambda: clock["now"]), \
            mock.patch.object(pose_endpoint.spring_client, "report_pose_data_batch") as mock_pose, \
            mock.patch.object(pose_endpoint.spring_client, "report_feedback_batch",
                              return_value=(spring_client.FeedbackBatchOutcome.OK, 1)), \
            mock.patch.object(pose_endpoint.spring_client, "send_rejected_attempt",
                              side_effect=on_rejected, return_value=True) as mock_rejected:
        responses = []
        for i in range(len(knees)):
            clock["now"] = 100.0 + i / 3
            responses.append(pose_endpoint.detect_pose(req))
    return responses, mock_pose, mock_rejected


def test_응답에_미인정_수와_사유가_실리고_시도마다_한_번_락_밖에서_전송된다():
    sid = 9401
    state = get_registry().create(session_id=sid, exercise_id=1, reference_angles=_REFERENCE)
    locked_during_send = []

    def on_rejected(**_kwargs):
        locked_during_send.append(state.state_lock.locked())
        return True

    try:
        responses, mock_pose, mock_rejected = _run_pose(sid, _SHALLOW + _SHALLOW + _DEEP, on_rejected=on_rejected)
    finally:
        get_registry().remove(sid)

    assert all(r.success for r in responses)
    reasons = [r.rejected_reason for r in responses]
    first, second = [i for i, reason in enumerate(reasons) if reason]
    assert (reasons[first], reasons[second]) == ("DEPTH_NOT_CONFIRMED", "DEPTH_NOT_CONFIRMED")
    # rejected_count 는 모든 응답에 실린다 — 미인정이 난 프레임부터 1, 2
    counts = [r.rejected_count for r in responses]
    assert counts == [0] * first + [1] * (second - first) + [2] * (len(responses) - second)
    # 안내 문구는 기존 message 그대로다
    assert responses[first].message == "이번 동작에서 충분히 내려간 장면을 확인하지 못했어요."

    # 미인정은 횟수·pose 배치·completed_reps 밖이다 — 인정 반복 1회만 그쪽으로 간다
    assert sum(r.rep_completed for r in responses) == 1
    assert responses[-1].rep_count == 1
    mock_pose.assert_called_once()
    assert len(state.completed_reps) == 1 and state.rep_count == 1

    # Spring 전송: 시도마다 한 번, 세션 락 밖에서
    assert mock_rejected.call_count == 2
    assert locked_during_send == [False, False]
    first_call, second_call = (c.kwargs for c in mock_rejected.call_args_list)
    assert (first_call["session_id"], first_call["attempt_number"], second_call["attempt_number"]) == (sid, 1, 2)
    assert first_call["reason"] == "DEPTH_NOT_CONFIRMED"
    assert 0.0 <= first_call["sync_rate"] <= 100.0
    # 가장 깊은 순간 — 세션 경과 1.0~2.0초(내려간 120° 구간)의 프레임, 그 프레임의 평활 무릎각·좌표
    assert 1.0 <= first_call["timestamp_sec"] <= 2.0
    assert 118.0 < first_call["min_knee_angle"] < 122.0
    assert len(json.loads(first_call["joint_coordinates"])) == 33


def test_시도_프레임이_없으면_끝_프레임으로_요약을_대신한다():
    terminal = PerRepFrame(timestamp_sec=3.5, joint_coordinates="[]", angles=[1.0],
                           smoothed_knee_angle=171.0, torso_tilt_deg=None)
    event = RejectedAttemptEvent(attempt_number=2, reason="SPLIT_STANCE", sync_rate=33.3, deepest_frame=None)
    with mock.patch.object(pose_endpoint.spring_client, "send_rejected_attempt") as send:
        pose_endpoint._report_rejected_attempt(77, event, terminal)
    send.assert_called_once_with(
        session_id=77, attempt_number=2, reason="SPLIT_STANCE", sync_rate=33.3,
        min_knee_angle=171.0, torso_tilt_deg=0.0, timestamp_sec=3.5, joint_coordinates="[]",
    )


# ─── 4. 재부착 ────────────────────────────────────────────────────────────────

_REFERENCE_POSES = [
    exercise_pb2.PoseDataRequest(timestamp_sec=0.0, joint_coordinates=_landmarks_to_json(_frame(170), 1.0))
]


@pytest.fixture
def servicer(monkeypatch):
    monkeypatch.setattr(
        exercise_servicer, "get_pool", lambda: Mock(acquire=lambda _: True, status=lambda: (0, 4))
    )
    created: list[int] = []
    yield exercise_servicer.ExerciseServicer(), created
    for sid in created:
        get_registry().remove(sid)


def test_ReattachAnalysis_는_initial_rejected_count_에서_잇는다(servicer):
    svc, created = servicer
    sid = 9410
    created.append(sid)
    get_registry().remove(sid)
    request = exercise_pb2.ReattachRequest(
        exercise_id=1, session_id=sid, reference_poses=_REFERENCE_POSES,
        initial_rep_count=3, initial_rejected_count=2,
    )
    assert svc.ReattachAnalysis(request, Mock()).success
    state = get_registry().get(sid)
    assert (state.rep_count, state.rejected_count) == (3, 2)
    # 분석기 카운터도 같은 값에서 시작한다 — 재부착 뒤 첫 미인정은 3번
    rejected, _ = _drive(state, _SHALLOW)
    assert [e.attempt_number for e in rejected] == [3]


def test_ReattachAnalysis_는_살아있는_세션의_미인정_수를_덮어쓰지_않는다(servicer):
    svc, created = servicer
    sid = 9411
    created.append(sid)
    live = get_registry().create(session_id=sid, exercise_id=1, reference_angles=_REFERENCE)
    live.rejected_count = 5
    request = exercise_pb2.ReattachRequest(
        exercise_id=1, session_id=sid, reference_poses=_REFERENCE_POSES, initial_rejected_count=1,
    )
    response = svc.ReattachAnalysis(request, Mock())
    assert response.success and response.already_active
    assert get_registry().get(sid).rejected_count == 5


def test_레지스트리는_미인정_수_기본값이_0이고_지정하면_저장한다():
    registry = SessionStateRegistry()
    assert registry.create(session_id=1, exercise_id=1, reference_angles=[]).rejected_count == 0
    assert registry.create(session_id=2, exercise_id=1, reference_angles=[],
                           initial_rejected_count=4).rejected_count == 4
    state, _ = registry.create_if_absent(session_id=3, exercise_id=1, reference_angles=[],
                                         initial_rejected_count=6)
    assert state.rejected_count == 6


# ─── 5. spring_client.send_rejected_attempt ──────────────────────────────────

_ARGS = dict(
    session_id=11, attempt_number=2, reason="DEPTH_NOT_CONFIRMED", sync_rate=61.5,
    min_knee_angle=104.2, torso_tilt_deg=12.3, timestamp_sec=5.12, joint_coordinates="[]",
)


def _stub(side_effect):
    stub = mock.MagicMock()
    stub.SaveRejectedAttempt.side_effect = side_effect
    return stub


def test_send_rejected_attempt_는_요청_필드와_데드라인을_싣는다():
    seen = []

    def record(request, **kwargs):
        seen.append((request, kwargs))
        return mock.MagicMock(success=True)

    with mock.patch.object(spring_client, "get_stub", return_value=_stub(record)):
        assert spring_client.send_rejected_attempt(**_ARGS) is True

    [(request, kwargs)] = seen
    assert isinstance(request, exercise_pb2.RejectedAttemptRequest)
    assert (request.session_id, request.attempt_number, request.reason) == (11, 2, "DEPTH_NOT_CONFIRMED")
    assert (request.sync_rate, request.min_knee_angle, request.torso_tilt_deg, request.timestamp_sec) == \
        pytest.approx((61.5, 104.2, 12.3, 5.12))
    assert request.joint_coordinates == "[]"
    # 🔴 None 이면 무한 대기 — pose 배치와 같은 데드라인이 실려야 한다(#206)
    assert kwargs["timeout"] == settings.BACKEND_GRPC_TIMEOUT_SECONDS
    assert kwargs["metadata"]


def test_구버전_Spring_의_UNIMPLEMENTED_는_던지지_않고_재시도_없이_한_번만_경고한다(monkeypatch, caplog):
    monkeypatch.setattr(spring_client, "_rejected_attempt_unimplemented_warned", False)
    sleep = Mock()
    monkeypatch.setattr(spring_client.time, "sleep", sleep)
    calls = []

    def unimplemented(_request, **_kwargs):
        calls.append(1)
        raise _FakeRpcError(grpc.StatusCode.UNIMPLEMENTED)

    with mock.patch.object(spring_client, "get_stub", return_value=_stub(unimplemented)), \
            caplog.at_level(logging.DEBUG, logger="app.grpc.spring_client"):
        assert spring_client.send_rejected_attempt(**_ARGS) is False
        assert spring_client.send_rejected_attempt(**_ARGS) is False

    assert len(calls) == 2  # 미인정마다 한 번씩만 — 재시도하지 않는다
    sleep.assert_not_called()
    warnings = [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert len(warnings) == 1 and "UNIMPLEMENTED" in warnings[0].getMessage()


def test_일시_장애는_pose_배치와_같은_백오프로_재시도한다(monkeypatch):
    sleeps = []
    monkeypatch.setattr(spring_client.time, "sleep", sleeps.append)
    outcomes = [_FakeRpcError(grpc.StatusCode.UNAVAILABLE), _FakeRpcError(grpc.StatusCode.DEADLINE_EXCEEDED),
                mock.MagicMock(success=True)]

    def flaky(_request, **_kwargs):
        outcome = outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    with mock.patch.object(spring_client, "get_stub", return_value=_stub(flaky)):
        assert spring_client.send_rejected_attempt(**_ARGS) is True
    assert sleeps == list(spring_client._POSE_BATCH_BACKOFF_SECONDS)


@pytest.mark.parametrize("code, expected_calls", [
    (grpc.StatusCode.NOT_FOUND, 1),      # 영구 실패 — 다시 던지지 않는다
    (grpc.StatusCode.UNAVAILABLE, 3),    # 일시 장애가 끝까지 — 3회 소진 후 버린다
])
def test_실패해도_던지지_않는다(monkeypatch, code, expected_calls):
    monkeypatch.setattr(spring_client.time, "sleep", lambda _s: None)
    calls = []

    def failing(_request, **_kwargs):
        calls.append(1)
        raise _FakeRpcError(code)

    with mock.patch.object(spring_client, "get_stub", return_value=_stub(failing)):
        assert spring_client.send_rejected_attempt(**_ARGS) is False
    assert len(calls) == expected_calls
