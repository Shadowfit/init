"""완료 통보의 «측정 상태» — total_reps 가 0 인 **이유**를 Spring 에 알린다.

0회라는 값만으로는 «사람이 화면에 없었다» 와 «사람은 있었는데 동작이 반복으로 인정되지 않았다» 가 같아 보인다.
그러면 앱이 사용자에게 «전신이 보이게 다시 찍어 보세요» 와 «동작을 더 크게 해 보세요» 를 가를 수 없다.

고정하는 것 셋:
  ① `SessionState.measurement_status` 의 판정 순서(임계값 없이 이미 세는 카운터만 쓴다).
  ② `report_complete_analysis` 가 그 값을 proto enum 으로 옮겨 실어 보낸다(접두사 MEASUREMENT_).
  ③ `_send_complete_analysis` 가 세션의 판정을 그대로 넘긴다 — 판정과 전송이 따로 노는 것을 막는다.

⚠️ 여기서 재는 것은 «카운터 → 상태» 의 번역이지 «카운터가 옳게 세어지는가» 가 아니다. 후자는 pose.py 쪽 시험의 몫이다.
"""

from __future__ import annotations

from unittest import mock

import pytest

import exercise_pb2
from app.grpc import exercise_servicer as servicer_mod
from app.grpc import spring_client
from app.grpc.session_state import get_registry

_REF = [[90.0, 170.0], [80.0, 165.0]]
_next_id = [700000]


def _state(**counters):
    """세션 하나를 만들고 카운터를 직접 정한다."""
    _next_id[0] += 1
    sid = _next_id[0]
    state = get_registry().create(session_id=sid, exercise_id=1, reference_angles=_REF, persona="BEGINNER")
    # create() 의 반환 형태에 기대지 않고 레지스트리에서 다시 읽는다(다른 테스트 파일과 같은 방식).
    state = get_registry().get(sid)
    for name, value in counters.items():
        setattr(state, name, value)
    return state


@pytest.mark.parametrize(
    "counters, expected",
    [
        # 1. 인정 반복 또는 미인정 시도가 하나라도 있으면 OK — 프레임 카운터가 어떻든
        (dict(rep_count=3, accepted_frame_count=30, visibility_skip_count=0), "OK"),
        (dict(rep_count=0, rejected_count=2, accepted_frame_count=30, visibility_skip_count=0), "OK"),
        # 재부착으로 이어받은 반복이 있으면 이후 프레임이 0 이어도 OK (이미 측정된 반복이 있다)
        (dict(rep_count=5, accepted_frame_count=0, visibility_skip_count=0), "OK"),
        # 2. 수락된 프레임이 하나도 없다 — 사람을 못 찾았거나(NO_POSE 갈래는 accept_frame 앞에서 반환) 프레임이 오지 않았다
        (dict(rep_count=0, rejected_count=0, accepted_frame_count=0, visibility_skip_count=0), "NO_POSE"),
        # 3. 수락됐지만 전부 가시성 부족으로 판정에서 빠졌다 — 하체가 화면 밖
        (dict(rep_count=0, rejected_count=0, accepted_frame_count=12, visibility_skip_count=12), "NOT_FULLY_VISIBLE"),
        # 4. 판정된 프레임은 있는데 반복도 시도도 0 — 서 있기만 했거나 동작이 인정 기준에 못 닿았다
        (dict(rep_count=0, rejected_count=0, accepted_frame_count=40, visibility_skip_count=5), "NO_REP"),
        (dict(rep_count=0, rejected_count=0, accepted_frame_count=1, visibility_skip_count=0), "NO_REP"),
    ],
)
def test_상태_판정_순서(counters, expected):
    assert _state(**counters).measurement_status == expected


def test_가시성_스킵이_일부만이면_NOT_FULLY_VISIBLE_이_아니다():
    """판정에 들어간 프레임이 하나라도 있으면(수락 - 스킵 > 0) «하체가 안 보였다» 로 단정하지 않는다."""
    s = _state(rep_count=0, rejected_count=0, accepted_frame_count=10, visibility_skip_count=9)
    assert s.judged_frame_count == 1
    assert s.measurement_status == "NO_REP"


@pytest.mark.parametrize("status", ["OK", "NO_POSE", "NOT_FULLY_VISIBLE", "NO_REP"])
def test_콜백이_상태를_proto_enum_으로_싣는다(status):
    sent = []

    def record(request, **_kwargs):
        sent.append(request)
        return mock.MagicMock(status=exercise_pb2.COMPLETED)

    stub = mock.MagicMock()
    stub.CompleteAnalysis.side_effect = record
    with mock.patch.object(spring_client, "get_stub", return_value=stub):
        spring_client.report_complete_analysis(1, 0, 0.0, measurement_status=status)

    assert len(sent) == 1
    assert sent[0].measurement_status == exercise_pb2.MeasurementStatus.Value(f"MEASUREMENT_{status}")


def test_상태를_안_주면_OK_다():
    """기본값 — 이 인자를 모르는 기존 호출부가 «측정 불가» 로 보내지 않는다."""
    sent = []
    stub = mock.MagicMock()
    stub.CompleteAnalysis.side_effect = lambda request, **_k: (sent.append(request), mock.MagicMock())[1]
    with mock.patch.object(spring_client, "get_stub", return_value=stub):
        spring_client.report_complete_analysis(1, 10, 80.0)

    assert sent[0].measurement_status == exercise_pb2.MEASUREMENT_OK


def test_proto_기본값_0_이_측정됨이다():
    """배포 순서가 어느 쪽이든 안 깨지는 근거 — 이 필드를 모르는 구버전 AI 의 메시지는 0 으로 읽힌다."""
    assert exercise_pb2.MeasurementStatus.Value("MEASUREMENT_OK") == 0
    assert exercise_pb2.SessionCompleteRequest().measurement_status == exercise_pb2.MEASUREMENT_OK


def test_완료_통보가_세션의_판정을_그대로_넘긴다():
    """`_send_complete_analysis` 가 state.measurement_status 를 spring_client 로 전달한다."""
    state = _state(rep_count=0, rejected_count=0, accepted_frame_count=0, visibility_skip_count=0)
    with mock.patch.object(servicer_mod, "spring_client") as client, \
            mock.patch("app.api.endpoints.pose.flush_pending_feedback"):
        servicer_mod._send_complete_analysis(state)

    kwargs = client.report_complete_analysis.call_args.kwargs
    assert kwargs["measurement_status"] == "NO_POSE"
    assert kwargs["total_reps"] == 0
