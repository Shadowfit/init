"""싱크로율 임계값 한 쌍(양호 · 주의)이 Spring 요청 → 세션 상태 → rep 판정까지 이어지는가 (2026-10-21).

사용자 결정: 임계값은 운동마다 한 쌍이고 Spring 이 StartAnalysis/ReattachAnalysis 로 내려준다.
페르소나는 더 이상 임계값을 바꾸지 않는다(트레이너 말투만). 0.0(proto3 «안 보냄»)이나 말이 안 되는
값은 기본값(80/60)으로 바뀐다.

무엇을 고정하나:
    1. StartAnalysis / ReattachAnalysis 가 요청의 두 값을 세션 상태에 싣는다 (depth_mode 와 같은 길).
    2. 재부착은 살아 있는 세션의 값을 덮어쓰지 않는다 (depth_mode 와 같은 규약).
    3. `_summarize_rep` 이 상태의 두 값으로 세 문구를 가른다 — 문구 자체는 그대로다
       (pose.py 의 BACK_BENT 게이트가 `!= "자세 양호"` 로 비교한다).
"""

from __future__ import annotations

from unittest.mock import Mock

import pytest

import exercise_pb2
from app.api.endpoints.pose import _landmarks_to_json
from app.core import squat_analyzer
from app.core.squat_analyzer import StreamingSquatAnalyzer
from app.grpc import exercise_servicer
from app.grpc.session_state import SessionState, SessionStateRegistry, get_registry
from app.utils.constants import DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN
from tests.test_squat_analyzer import _frame

# 재부착은 기준 각도가 비면 실패로 돌린다 — 선 자세 한 프레임짜리 기준을 싣는다.
_REFERENCE = [
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


def _start(servicer, sid: int, **thresholds):
    svc, created = servicer
    created.append(sid)
    get_registry().remove(sid)
    request = exercise_pb2.AnalyzeRequest(
        exercise_id=1, session_id=sid, reference_poses=_REFERENCE, **thresholds
    )
    assert svc.StartAnalysis(request, Mock()).success
    return get_registry().get(sid)


def _reattach(servicer, sid: int, **thresholds):
    svc, created = servicer
    created.append(sid)
    request = exercise_pb2.ReattachRequest(
        exercise_id=1, session_id=sid, reference_poses=_REFERENCE, **thresholds
    )
    response = svc.ReattachAnalysis(request, Mock())
    assert response.success
    return response, get_registry().get(sid)


# ─── StartAnalysis ───────────────────────────────────────────────────────────


def test_StartAnalysis_는_요청의_임계값을_세션에_싣는다(servicer):
    state = _start(servicer, 91001, sync_good_threshold=85.0, sync_warn_threshold=65.0)
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (85.0, 65.0)


def test_StartAnalysis_는_임계값이_없으면_기본값을_쓰고_경고한다(servicer, caplog):
    with caplog.at_level("WARNING", logger="app.grpc.exercise_servicer"):
        state = _start(servicer, 91002)  # proto3 — 안 보내면 0.0
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN)
    assert any("싱크로율 임계값" in r.getMessage() for r in caplog.records)


@pytest.mark.parametrize(
    "good, warn",
    [(60.0, 70.0), (70.0, 70.0), (120.0, 130.0), (-1.0, -2.0)],
)
def test_StartAnalysis_는_잘못된_임계값을_기본값으로_바꾼다(servicer, good, warn):
    state = _start(servicer, 91003, sync_good_threshold=good, sync_warn_threshold=warn)
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN)


def test_StartAnalysis_는_페르소나로_임계값을_바꾸지_않는다(servicer):
    svc, created = servicer
    for sid, persona in ((91004, "ADVANCED"), (91005, "REHAB")):
        created.append(sid)
        request = exercise_pb2.AnalyzeRequest(
            exercise_id=1, session_id=sid, persona=persona, reference_poses=_REFERENCE,
            sync_good_threshold=75.0, sync_warn_threshold=55.0,
        )
        assert svc.StartAnalysis(request, Mock()).success
        state = get_registry().get(sid)
        assert (state.sync_good_threshold, state.sync_warn_threshold) == (75.0, 55.0)


# ─── ReattachAnalysis ────────────────────────────────────────────────────────


def test_ReattachAnalysis_는_새로_만든_상태에_요청의_임계값을_싣는다(servicer):
    get_registry().remove(91010)
    response, state = _reattach(servicer, 91010, sync_good_threshold=88.0, sync_warn_threshold=70.0)
    assert response.already_active is False
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (88.0, 70.0)


def test_ReattachAnalysis_는_임계값이_없으면_기본값을_쓴다(servicer):
    get_registry().remove(91011)
    _, state = _reattach(servicer, 91011)
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN)


def test_ReattachAnalysis_는_살아있는_세션의_임계값을_덮어쓰지_않는다(servicer):
    _start(servicer, 91012, sync_good_threshold=85.0, sync_warn_threshold=65.0)
    response, state = _reattach(servicer, 91012, sync_good_threshold=70.0, sync_warn_threshold=50.0)
    assert response.already_active is True
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (85.0, 65.0)


# ─── 레지스트리 ───────────────────────────────────────────────────────────────


def test_레지스트리는_임계값_기본값이_80_60이고_지정하면_저장한다():
    registry = SessionStateRegistry()
    default_state = registry.create(session_id=1, exercise_id=1, reference_angles=[])
    assert (default_state.sync_good_threshold, default_state.sync_warn_threshold) == (80.0, 60.0)

    state, _ = registry.create_if_absent(
        session_id=2, exercise_id=1, reference_angles=[],
        sync_good_threshold=90.0, sync_warn_threshold=70.0,
    )
    assert (state.sync_good_threshold, state.sync_warn_threshold) == (90.0, 70.0)


# ─── rep 판정 (_summarize_rep) ────────────────────────────────────────────────


def _message_for(monkeypatch, score: float, **state_kwargs) -> str:
    monkeypatch.setattr(squat_analyzer, "compute_sync_rate", lambda *_, **__: score)
    state = SessionState(session_id=1, exercise_id=1, reference_angles=[[1.0, 2.0, 3.0, 4.0]], **state_kwargs)
    event = StreamingSquatAnalyzer("squat")._summarize_rep(state, [1.0, 2.0, 3.0, 4.0])
    assert event.sync_rate == score
    return event.feedback_message


def test_rep_판정은_세션의_임계값으로_가른다(monkeypatch):
    kw = {"sync_good_threshold": 75.0, "sync_warn_threshold": 50.0}
    assert _message_for(monkeypatch, 75.0, **kw) == "자세 양호"
    assert _message_for(monkeypatch, 74.9, **kw) == "자세 보정 필요"
    assert _message_for(monkeypatch, 50.0, **kw) == "자세 보정 필요"
    assert _message_for(monkeypatch, 49.9, **kw) == "즉시 자세 수정 필요"


def test_rep_판정의_기본값은_80_60이고_페르소나와_무관하다(monkeypatch):
    for persona in ("BEGINNER", "ADVANCED", "DIET", "REHAB"):
        assert _message_for(monkeypatch, 80.0, persona=persona) == "자세 양호"
        assert _message_for(monkeypatch, 70.0, persona=persona) == "자세 보정 필요"
        assert _message_for(monkeypatch, 59.0, persona=persona) == "즉시 자세 수정 필요"


def test_rep_판정은_상태의_0_임계값을_기본값으로_읽는다(monkeypatch):
    kw = {"sync_good_threshold": 0.0, "sync_warn_threshold": 0.0}
    assert _message_for(monkeypatch, 79.0, **kw) == "자세 보정 필요"
    assert _message_for(monkeypatch, 59.0, **kw) == "즉시 자세 수정 필요"
