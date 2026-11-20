"""방치된 세션 정리(session_reaper)와 StartAnalysis 멱등.

[왜 필요한가] 세션 상태는 프로세스 메모리에만 있고 지우는 곳이 `StopAnalysis` 하나였다. 그 통보가 안 오면 상태와
검출기(약 100MB)가 재기동까지 남아 풀(= 동시 세션 상한)이 줄어든다. 그리고 `StartAnalysis` 가 같은 id 로 다시 오면
`create()` 가 진행 중이던 상태를 덮어써 세션이 처음으로 되돌아갔다.

시간은 `now=` 로 주입한다(accept_frame 과 같은 방식) — 실제로 기다리지 않는다.
"""

from __future__ import annotations

import threading
from unittest.mock import patch

import exercise_pb2
from app.grpc import session_reaper
from app.grpc.exercise_servicer import ExerciseServicer
from app.grpc.session_state import SessionStateRegistry
from app.observability import metrics

SQUAT_ID = 1


class _FakePool:
    """DetectorPool 대역 — 반납된 세션을 기록한다. `fail_on` 의 세션은 반납이 예외로 실패한다."""

    def __init__(self, fail_on: set[int] | None = None) -> None:
        self.released: list[int] = []
        self._fail_on = fail_on or set()

    def release(self, session_id: int) -> bool:
        if session_id in self._fail_on:
            raise RuntimeError("close 실패")
        self.released.append(session_id)
        return True

    # StartAnalysis 경로용
    def status(self):
        return 0, 10

    def acquire(self, _session_id):
        return True


def _make(registry: SessionStateRegistry, session_id: int):
    return registry.create(session_id=session_id, exercise_id=SQUAT_ID, reference_angles=[])


# ─── 방치 판정 ──────────────────────────────────────────────────────────


def test_유입이_없는_세션만_정리한다():
    registry = SessionStateRegistry()
    idle = _make(registry, 1)
    alive = _make(registry, 2)
    idle.last_seen_mono = 100.0
    alive.last_seen_mono = 3_500.0

    reaped = registry.reap_idle(max_idle_sec=3_600, now=3_800.0)  # idle: 3700초 무소식, alive: 300초

    assert reaped == [1]
    assert registry.exists(1) is False
    assert registry.exists(2) is True


def test_상한과_같은_시간은_아직_정리하지_않는다():
    registry = SessionStateRegistry()
    state = _make(registry, 1)
    state.last_seen_mono = 100.0

    assert registry.reap_idle(max_idle_sec=3_600, now=3_700.0) == []   # 정확히 3600초
    assert registry.reap_idle(max_idle_sec=3_600, now=3_700.5) == [1]  # 넘으면 정리


def test_한_번도_안_건드린_세션은_생성_시각부터_센다():
    registry = SessionStateRegistry()
    state = _make(registry, 1)
    state.created_mono = 50.0
    assert state.last_seen_mono is None

    assert registry.reap_idle(max_idle_sec=100, now=140.0) == []
    assert registry.reap_idle(max_idle_sec=100, now=151.0) == [1]


def test_get_은_세션이_살아_있다고_기록한다():
    """/pose 프레임 경로가 `get()` 으로 상태를 찾는다 — 프레임이 오는 세션은 방치가 아니다."""
    registry = SessionStateRegistry()
    state = _make(registry, 1)
    state.created_mono = 0.0   # 아주 오래전에 만들어졌더라도
    state.last_seen_mono = 0.0

    registry.get(1)            # 방금 프레임이 왔다

    assert state.last_seen_mono is not None and state.last_seen_mono > 0.0
    assert registry.reap_idle(max_idle_sec=3_600) == []


def test_재부착도_세션이_살아_있다고_기록한다():
    registry = SessionStateRegistry()
    state = _make(registry, 1)
    state.last_seen_mono = 0.0

    _, already_active = registry.create_if_absent(session_id=1, exercise_id=SQUAT_ID, reference_angles=[])

    assert already_active is True
    assert state.last_seen_mono > 0.0


def test_정리된_세션은_최근_종료_기록을_남기지_않는다():
    """정리를 «정상 종료» 로 위장하지 않는다 — 늦게 온 StopAnalysis 가 «세션 없음» 으로 보여야 한다."""
    registry = SessionStateRegistry()
    state = _make(registry, 1)
    state.last_seen_mono = 0.0

    registry.reap_idle(max_idle_sec=10, now=100.0)

    assert registry.was_recently_stopped(1) is False


# ─── 정리 한 번 (검출기 반납·지표) ───────────────────────────────────────


def test_정리하면_검출기를_반납하고_지표를_올린다():
    registry = SessionStateRegistry()
    for sid in (1, 2, 3):
        _make(registry, sid).last_seen_mono = 0.0
    pool = _FakePool()
    before = metrics.sessions_reaped_total._value.get()

    reaped = session_reaper.reap_once(registry, pool, max_idle_sec=10, now=100.0)

    assert sorted(reaped) == [1, 2, 3]
    assert sorted(pool.released) == [1, 2, 3]
    assert metrics.sessions_reaped_total._value.get() == before + 3


def test_한_세션의_반납이_실패해도_나머지는_정리한다():
    registry = SessionStateRegistry()
    for sid in (1, 2, 3):
        _make(registry, sid).last_seen_mono = 0.0
    pool = _FakePool(fail_on={2})

    reaped = session_reaper.reap_once(registry, pool, max_idle_sec=10, now=100.0)

    assert sorted(reaped) == [1, 2, 3]                 # 상태는 전부 지웠다
    assert sorted(pool.released) == [1, 3]             # 반납은 실패한 하나만 빠졌다
    assert registry.active_count() == 0


def test_정리할_것이_없으면_지표도_안_오른다():
    registry = SessionStateRegistry()
    _make(registry, 1).last_seen_mono = 99.0
    before = metrics.sessions_reaped_total._value.get()

    assert session_reaper.reap_once(registry, _FakePool(), max_idle_sec=10, now=100.0) == []
    assert metrics.sessions_reaped_total._value.get() == before


def test_run_reaper_는_stop_이_set_되면_끝난다():
    stop = threading.Event()
    stop.set()
    with patch("app.core.mediapipe_detector.get_pool", return_value=_FakePool()):
        session_reaper.run_reaper(stop, interval_sec=0.01, max_idle_sec=10)   # 돌아오면 통과 — 안 돌아오면 테스트가 멈춘다


# ─── StartAnalysis 멱등 ─────────────────────────────────────────────────


class _Ctx:
    def abort(self, code, details):  # pragma: no cover - 이 테스트들은 abort 되면 안 된다
        raise AssertionError(f"abort 되면 안 된다: {code} {details}")


def _start(session_id: int):
    servicer = ExerciseServicer()
    request = exercise_pb2.AnalyzeRequest(exercise_id=SQUAT_ID, session_id=session_id, persona="BEGINNER")
    with patch("app.grpc.exercise_servicer.get_pool", return_value=_FakePool()):
        return servicer.StartAnalysis(request, _Ctx())


def test_StartAnalysis_가_같은_id_로_다시_오면_진행_중인_상태를_덮어쓰지_않는다():
    from app.grpc.session_state import get_registry

    session_id = 777_001
    first = _start(session_id)
    assert first.success is True
    state = get_registry().get(session_id)
    state.rep_count = 5                       # 운동이 진행되던 중
    state.session_nonce = "owner-secret"

    second = _start(session_id)               # 재시도·중복 호출

    assert second.success is True              # 성공으로 답한다 — Spring 이 실패로 오해하지 않게
    same = get_registry().get(session_id)
    assert same is state                       # 새로 만들지 않았다
    assert same.rep_count == 5                 # 진행이 되돌아가지 않았다
    assert same.session_nonce == "owner-secret"

    get_registry().remove(session_id)


def test_StartAnalysis_첫_호출은_새_상태를_만든다():
    from app.grpc.session_state import get_registry

    session_id = 777_002
    assert get_registry().exists(session_id) is False

    response = _start(session_id)

    assert response.success is True
    assert get_registry().exists(session_id) is True
    get_registry().remove(session_id)
