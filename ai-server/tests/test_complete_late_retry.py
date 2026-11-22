"""CompleteAnalysis «느린 재시도» (#799) — 즉시 재시도(3회)가 다 실패해도 창 안에서 타이머로 계속 시도한다.

고정하는 것:
  ① 즉시 재시도를 다 쓰면 **exhausted 로 세지 않고** deferred 로 넘기며 대기 건수가 오른다(아직 유실이 아니다).
  ② 느린 시도가 성공하면 recovered_late 로 세고 대기 건수가 내려가며 더 이상 타이머를 걸지 않는다.
  ③ 실패하면 창이 남은 만큼만 다음 타이머를 건다. 창을 다 쓰면 그때 exhausted — **유실은 여기서만 센다.**
  ④ 영구 실패(NOT_FOUND 등)는 느린 시도에서도 재시도하지 않는다. 예외가 나도 대기 건수가 새지 않는다.
  ⑤ 창이 0 이면 예전 동작(즉시 재시도 뒤 바로 포기).
  ⑥ 풀 워커를 붙잡지 않는다 — 느린 재시도는 sleep 이 아니라 타이머다.

⚠️ 실제 Spring 은 안 띄운다. 스텁을 가짜로 갈아끼워 «우리가 언제 몇 번 던지는가» 만 본다. 타이머는 기본적으로 포착해서 손으로 울린다.
"""
from __future__ import annotations

import contextvars
import threading
import time
from unittest import mock

import grpc
import pytest
from prometheus_client import REGISTRY

from app.config import settings
from app.grpc import spring_client


# 공용 time.sleep 은 일부 시험이 monkeypatch 로 무동작으로 바꾼다(즉시 재시도 백오프를 안 기다리려고). 진짜 대기가 필요한 자리용으로
# 모듈을 불러오는 시점(= 아직 아무도 안 바꿨다)의 값을 보관한다.
_REAL_SLEEP = time.sleep


class _Err(grpc.RpcError):
    def __init__(self, code):
        super().__init__()
        self._code = code

    def code(self):
        return self._code

    def details(self):
        return f"fake {self._code}"


def _count(outcome: str) -> float:
    return REGISTRY.get_sample_value(
        "shadowfit_ai_spring_callback_total", {"rpc": "CompleteAnalysis", "outcome": outcome}) or 0.0


def _pending() -> float:
    return REGISTRY.get_sample_value("shadowfit_ai_complete_callback_late_pending") or 0.0


class _Harness:
    """스텁 응답을 순서대로 돌려주고, 타이머 생성을 포착한다."""

    def __init__(self, monkeypatch, results):
        self.results = list(results)   # "ok" 또는 grpc.StatusCode — 호출 순서대로 소비
        self.calls = []
        self.timers = []               # (delay, fn, args)
        monkeypatch.setattr(settings, "COMPLETE_RETRY_WINDOW_SEC", 600)
        monkeypatch.setattr(settings, "COMPLETE_RETRY_INTERVAL_SEC", 30)
        monkeypatch.setattr(spring_client.time, "sleep", lambda _s: None)     # 즉시 재시도의 백오프를 기다리지 않는다
        monkeypatch.setattr(spring_client, "_start_timer", lambda d, fn, a: self.timers.append((d, fn, a)))
        stub = mock.MagicMock()
        stub.CompleteAnalysis.side_effect = self._answer
        monkeypatch.setattr(spring_client, "get_stub", lambda: stub)

    def _answer(self, request, **kwargs):
        self.calls.append((request, kwargs))
        r = self.results.pop(0)
        if r == "ok":
            return mock.MagicMock(status=1)
        raise _Err(r)

    def fire_next_timer(self):
        delay, fn, args = self.timers.pop(0)
        fn(*args)
        return delay


U = grpc.StatusCode.UNAVAILABLE


def _report(*args, **kwargs):
    """report_complete_analysis 를 **복사한 컨텍스트 안에서** 부른다.

    이 함수는 `call_metadata()` 로 correlation id 를 만들어 현재 컨텍스트에 심는다. 운영에서는 격리된 콜백 풀 스레드라 문제가 없지만, 시험이
    테스트 스레드에서 직접 부르면 그 id 가 다음 시험(`test_correlation` 의 «빠져나온 뒤에는 원복»)으로 새어 나간다.
    """
    return contextvars.copy_context().run(spring_client.report_complete_analysis, *args, **kwargs)


def test_즉시_재시도를_다_쓰면_exhausted_가_아니라_deferred_로_넘긴다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U])
    before = {o: _count(o) for o in ("ok", "retried", "exhausted", "deferred")}
    pend = _pending()

    _report(1, 5, 80.0)

    assert len(h.calls) == 3                                           # 즉시 재시도 3회
    assert _count("exhausted") == before["exhausted"], "아직 유실이 아니다 — 느린 재시도가 남아 있다"
    assert _count("deferred") == before["deferred"] + 1
    assert _count("retried") == before["retried"] + 2                  # 1→2, 2→3 사이
    assert _pending() == pend + 1
    assert len(h.timers) == 1 and h.timers[0][0] == 30                 # COMPLETE_RETRY_INTERVAL_SEC


def test_느린_시도가_성공하면_recovered_late_로_세고_대기가_내려간다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U, "ok"])
    _report(1, 5, 80.0)
    pend = _pending()                       # 위 호출이 +1 한 뒤
    rec, exh = _count("recovered_late"), _count("exhausted")

    h.fire_next_timer()

    assert len(h.calls) == 4
    assert _count("recovered_late") == rec + 1
    assert _count("exhausted") == exh
    assert _pending() == pend - 1
    assert h.timers == [], "성공했으면 더 이상 타이머를 걸지 않는다"


def test_느린_시도가_실패해도_창이_남았으면_다음_타이머를_건다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U, U])
    _report(1, 5, 80.0)
    pend, ret = _pending(), _count("retried")

    h.fire_next_timer()

    assert len(h.timers) == 1, "창(600초)이 남았으니 30초 뒤에 다시"
    assert _count("retried") == ret + 1
    assert _pending() == pend, "아직 끝나지 않았으니 대기 건수는 그대로"


def test_창을_다_쓰면_그때_exhausted_로_센다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U, U])
    _report(1, 5, 80.0)
    pend, exh = _pending(), _count("exhausted")
    # 창이 사실상 끝난 시점으로 만든다 — 다음 간격을 더하면 deadline 을 넘는다
    delay, fn, (req, meta, sid, deadline) = h.timers.pop(0)
    expired = (req, meta, sid, time.monotonic() + 1)    # 남은 1초 < 간격 30초

    fn(*expired)

    assert _count("exhausted") == exh + 1
    assert _pending() == pend - 1
    assert h.timers == []


def test_영구_실패는_느린_시도에서도_재시도하지_않는다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U, grpc.StatusCode.NOT_FOUND])
    _report(1, 5, 80.0)
    pend, rej, exh = _pending(), _count("rejected"), _count("exhausted")

    h.fire_next_timer()

    assert _count("rejected") == rej + 1
    assert _count("exhausted") == exh, "거절은 유실이 아니라 상대가 받지 않겠다는 답이다"
    assert _pending() == pend - 1
    assert h.timers == []


def test_타이머_안에서_예외가_나도_대기_건수가_새지_않는다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U])
    _report(1, 5, 80.0)
    pend, exh = _pending(), _count("exhausted")
    monkeypatch.setattr(spring_client, "get_stub", mock.MagicMock(side_effect=RuntimeError("boom")))

    h.fire_next_timer()      # 던지지 않아야 한다(타이머 스레드에서 터지면 아무도 모른다)

    assert _count("exhausted") == exh + 1
    assert _pending() == pend - 1


def test_창이_0_이면_예전_동작_즉시_재시도_뒤_바로_포기(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U])
    monkeypatch.setattr(settings, "COMPLETE_RETRY_WINDOW_SEC", 0)
    exh, dfr, pend = _count("exhausted"), _count("deferred"), _pending()

    _report(1, 5, 80.0)

    assert _count("exhausted") == exh + 1
    assert _count("deferred") == dfr
    assert _pending() == pend
    assert h.timers == []


def test_즉시_재시도에서_성공하면_느린_재시도를_걸지_않는다(monkeypatch):
    h = _Harness(monkeypatch, [U, "ok"])
    ok, pend = _count("ok"), _pending()

    _report(1, 5, 80.0)

    assert _count("ok") == ok + 1
    assert _pending() == pend
    assert h.timers == []


def test_느린_시도도_같은_요청과_메타데이터를_다시_보낸다(monkeypatch):
    """한 호출의 시도들이 서로 묶이고(같은 correlation id), 내용이 같아야 Spring 의 멱등 처리가 중복을 흡수한다."""
    h = _Harness(monkeypatch, [U, U, U, "ok"])
    _report(42, 7, 81.5, measurement_status="NO_REP")
    h.fire_next_timer()

    first_req, first_kwargs = h.calls[0]
    late_req, late_kwargs = h.calls[-1]
    assert late_req == first_req and late_req.session_id == 42 and late_req.total_reps == 7
    assert late_kwargs["metadata"] == first_kwargs["metadata"]
    assert late_kwargs["timeout"] == settings.BACKEND_GRPC_TIMEOUT_SECONDS    # 느린 시도에도 데드라인이 실린다


def test_실제_타이머로_Spring_이_돌아오면_결과가_되살아난다(monkeypatch):
    """포착 없이 진짜 threading.Timer 로 — 풀 워커가 아니라 타이머 스레드가 이어 간다."""
    monkeypatch.setattr(settings, "COMPLETE_RETRY_WINDOW_SEC", 30)
    monkeypatch.setattr(settings, "COMPLETE_RETRY_INTERVAL_SEC", 1)
    monkeypatch.setattr(spring_client.time, "sleep", lambda _s: None)
    done = threading.Event()
    answers = [U, U, U, U, "ok"]          # 즉시 3회 + 느린 시도 1회 실패 + 느린 시도 2회째 성공
    calls = []

    def answer(request, **_kw):
        calls.append(time.monotonic())
        r = answers.pop(0)
        if r == "ok":
            done.set()
            return mock.MagicMock(status=1)
        raise _Err(r)

    stub = mock.MagicMock()
    stub.CompleteAnalysis.side_effect = answer
    monkeypatch.setattr(spring_client, "get_stub", lambda: stub)
    rec, pend0 = _count("recovered_late"), _pending()

    t0 = time.monotonic()
    _report(9, 3, 70.0)
    returned_in = time.monotonic() - t0

    # 간격이 1초라, 호출이 느린 재시도를 기다려 막혔다면 최소 1초가 걸린다 — 그보다 한참 짧으면 풀 워커를 바로 놓아 준 것이다.
    assert returned_in < 0.9, "즉시 재시도가 끝나면 호출자(풀 워커)를 바로 놓아준다 — 느린 재시도는 타이머가 한다"
    assert done.wait(10), "느린 재시도가 성공까지 이어지지 않았다"
    # `done` 은 스텁이 반환하기 **전에** 켜지므로(그 뒤에 지표가 오른다) 그 신호만 믿으면 단언이 지표보다 앞설 수 있다.
    # 관측할 결과(지표·대기 건수)가 실제로 바뀔 때까지 기다린다. 공용 time.sleep 은 위에서 무동작으로 바꿨으므로 진짜 sleep 을 쓴다.
    end = time.monotonic() + 10
    while (_count("recovered_late") < rec + 1 or _pending() != pend0) and time.monotonic() < end:
        _REAL_SLEEP(0.02)
    assert _count("recovered_late") == rec + 1
    assert _pending() == pend0, "성공했으면 대기 건수가 처음 값으로 돌아와야 한다"
    assert len(calls) == 5


def test_창이_간격보다_짧으면_느린_재시도를_예약하지_않고_바로_유실로_세고_대기도_안_올린다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U])
    monkeypatch.setattr(settings, "COMPLETE_RETRY_WINDOW_SEC", 10)       # 간격 30초보다 짧다 — 다음 시도가 창 안에 못 든다
    exh, dfr, pend = _count("exhausted"), _count("deferred"), _pending()

    _report(1, 5, 80.0)

    assert h.timers == [], "창이 끝난 뒤에 결과를 쥐고 요청을 보내면 안 된다"
    assert _count("exhausted") == exh + 1
    assert _count("deferred") == dfr
    assert _pending() == pend


def test_타이머를_못_만들면_유실로_마무리하고_대기_건수를_되돌린다(monkeypatch):
    _Harness(monkeypatch, [U, U, U])

    def boom(_d, _fn, _a):
        raise RuntimeError("can't start new thread")

    monkeypatch.setattr(spring_client, "_start_timer", boom)
    exh, dfr, pend = _count("exhausted"), _count("deferred"), _pending()

    _report(1, 5, 80.0)      # 던지지 않아야 한다 — 호출자는 풀 워커다

    assert _count("exhausted") == exh + 1
    assert _count("deferred") == dfr, "예약하지 못했으니 deferred 로 세지 않는다"
    assert _pending() == pend, "올렸던 대기 건수를 되돌린다"


def test_타이머가_늦게_울려_창이_이미_끝났으면_요청을_보내지_않고_마무리한다(monkeypatch):
    h = _Harness(monkeypatch, [U, U, U])
    _report(1, 5, 80.0)
    pend, exh, calls = _pending(), _count("exhausted"), len(h.calls)
    delay, fn, (req, meta, sid, _deadline) = h.timers.pop(0)

    fn(req, meta, sid, time.monotonic() - 1)       # 이미 지난 deadline

    assert len(h.calls) == calls, "창이 끝난 뒤에는 요청을 보내지 않는다"
    assert _count("exhausted") == exh + 1
    assert _pending() == pend - 1
