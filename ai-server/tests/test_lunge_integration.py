from unittest.mock import Mock

import numpy as np
import pytest

import exercise_pb2
from app.api.endpoints import pose
from app.grpc import exercise_servicer
from app.grpc.session_state import PerRepFrame, get_registry
from app.models.pose import PoseRequest, PoseSkipReason
from tests.test_lunge_analyzer import frame
from tests.test_pose_response_contract import _fake_lease


@pytest.fixture
def session(monkeypatch):
    sid = 92501
    get_registry().remove(sid)
    monkeypatch.setattr(exercise_servicer, "get_pool", lambda: Mock(acquire=lambda _: True, status=lambda: (0, 4)))
    request = exercise_pb2.AnalyzeRequest(exercise_id=2, session_id=sid, session_nonce="owner")
    assert exercise_servicer.ExerciseServicer().StartAnalysis(request, Mock()).success
    state = get_registry().get(sid)
    yield state
    get_registry().remove(sid)


def test_lunge_http_frames_emit_one_persistable_rep_and_no_squat_feedback(session, monkeypatch):
    monkeypatch.setattr(pose, "base64_to_image", lambda _: np.zeros((100, 100, 3), dtype=np.uint8))
    report = Mock(return_value=True)
    concurrent_frame = PerRepFrame(99, "[]", [175, 175])
    def accept_next_frame_during_callback(*_):
        with session.state_lock:
            session.current_rep_frames.append(concurrent_frame)
        return True
    report.side_effect = accept_next_frame_during_callback
    feedback = Mock(return_value=True)
    monkeypatch.setattr(pose.spring_client, "report_pose_data_batch", report)
    monkeypatch.setattr(pose, "flush_pending_feedback", feedback)
    req = PoseRequest(image="", session_id=session.session_id, session_nonce="owner", exercise_type="squat")
    # Request type must not override the authoritative session exercise type.
    now = 0.
    for kwargs in ({}, {"left_step": .3, "left": 145, "right": 145},
                   {"left_step": .3, "left": 90, "right": 85}, {"left_step": .3}, {}):
        for _ in range(3):
            now += .34
            monkeypatch.setattr(pose.time, "monotonic", lambda: now)
            monkeypatch.setattr(pose, "lease_detector", _fake_lease(lambda _: frame(**kwargs)))
            response = pose.detect_pose(req)
            assert response.success
            assert response.lunge is not None
            assert response.sync_rate is None
    assert response.rep_count == 1
    assert response.lunge.left_reps == 1
    report.assert_called_once()
    frames = report.call_args.args[1]
    assert frames and all(f.rep_number == 1 for f in frames)
    # 2026-09-27: 좌우별 깊이 채점을 백엔드에서 하려면 어느 다리인지가 프레임 단위로
    # pose_data까지 내려가야 한다 — left_step을 준 이 rep은 "left"가 나가야 한다.
    assert all(f.lead_leg == "left" for f in frames)
    assert not session.pending_feedback_events
    assert concurrent_frame in session.current_rep_frames


def test_no_pose_from_nonowner_cannot_reset_lunge(session, monkeypatch):
    from app.core.lunge_analyzer import LungeState
    session.lunge_state = LungeState(phase="returning", lead_leg="left")
    monkeypatch.setattr(pose, "base64_to_image", lambda _: np.zeros((4, 4, 3), dtype=np.uint8))
    monkeypatch.setattr(pose, "lease_detector", _fake_lease(lambda _: []))
    response = pose.detect_pose(PoseRequest(image="", session_id=session.session_id, session_nonce="wrong"))
    assert response.skip_reason == PoseSkipReason.NO_POSE
    assert session.lunge_state.phase == "returning"
    pose.detect_pose(PoseRequest(image="", session_id=session.session_id, session_nonce="owner"))
    assert session.lunge_state.phase == "ready"


def test_lunge_reattach_without_reference_restores_total_and_preserves_live_state(session):
    servicer = exercise_servicer.ExerciseServicer()
    req = exercise_pb2.ReattachRequest(exercise_id=2, session_id=session.session_id,
                                      initial_rep_count=7, session_nonce="owner")
    live = servicer.ReattachAnalysis(req, Mock())
    assert live.success and live.already_active and live.rep_count == 0
    get_registry().remove(session.session_id)
    restored = servicer.ReattachAnalysis(req, Mock())
    assert restored.success and not restored.already_active and restored.rep_count == 7
