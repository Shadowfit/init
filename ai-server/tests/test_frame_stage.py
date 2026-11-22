"""프레임 경로 구간 시간을 «상시» 재는지 (결함 ④).

`frame_path.py` 는 기본이 꺼져 있어 운영에서는 AI 구간이 안 보였다. 이 테스트가 지키는 것은
세 가지다 — **기본 구성에서 기록되는가 · cid 로 이어지는가 · 기본 구성에서 게이지가 거짓말을
안 하는가.**

⚠️ 구간 값의 «크기» 는 단언하지 않는다. 합성 타임스탬프로 **어느 구간에 얼마가 들어가는지**만 본다 —
   실제 추론 시간은 박스마다 다르고 이 테스트의 질문이 아니다.
"""

from __future__ import annotations

import asyncio
import logging
import threading
import unittest
from unittest import mock

from fastapi.testclient import TestClient

from app.config import settings
from app.grpc.correlation import CorrelationHttpMiddleware
from app.grpc.session_state import get_registry
from app.main import app
from app.observability import frame_stage, metrics


def _count(stage: str) -> float:
    """히스토그램 `_count` 샘플. 전역 레지스트리라 판 사이 누적 — 항상 «차» 로 본다."""
    value = metrics.REGISTRY.get_sample_value(
        "shadowfit_ai_frame_stage_seconds_count", {"stage": stage}
    )
    return value or 0.0


def _sum(stage: str) -> float:
    value = metrics.REGISTRY.get_sample_value(
        "shadowfit_ai_frame_stage_seconds_sum", {"stage": stage}
    )
    return value or 0.0


class FrameStageRecording(unittest.TestCase):
    def test_records_each_stage_with_its_own_span(self):
        """세 구간이 각자의 길이로 들어간다 — 합쳐서 한 칸에 넣으면 «어디가 느린가» 가 안 갈린다."""
        before = {s: (_count(s), _sum(s)) for s in metrics.FRAME_STAGES}

        token = frame_stage._arrival.set(100.0)
        try:
            # 도착 100.0 → 워커 진입 100.5 → 검출기 획득 101.0 → 추론 완료 103.0
            frame_stage.record(t_handler_in=100.5, t_leased=101.0, t_inferred=103.0)
        finally:
            frame_stage._arrival.reset(token)

        for stage, expected in (("wait", 0.5), ("lease", 0.5), ("infer", 2.0)):
            c0, s0 = before[stage]
            self.assertEqual(_count(stage) - c0, 1, f"{stage} 가 한 번 기록돼야 한다")
            self.assertAlmostEqual(_sum(stage) - s0, expected, places=6, msg=stage)

    def test_missing_arrival_skips_wait_instead_of_recording_zero(self):
        """🔴 도착 시각이 없을 때 wait 를 0 으로 채우면 «기다림 없음» 으로 읽힌다 — 건너뛴다."""
        wait0, lease0 = _count("wait"), _count("lease")

        token = frame_stage._arrival.set(None)
        try:
            frame_stage.record(t_handler_in=10.0, t_leased=10.1, t_inferred=10.2)
        finally:
            frame_stage._arrival.reset(token)

        self.assertEqual(_count("wait"), wait0, "도착 시각 없이 wait 를 기록하면 안 된다")
        self.assertEqual(_count("lease") - lease0, 1)

    def test_never_raises_into_the_frame_path(self):
        """계측이 프레임 처리를 깨뜨리면 안 된다."""
        with mock.patch.object(metrics, "observe_frame_stage", side_effect=RuntimeError("boom")):
            frame_stage.record(1.0, 2.0, 3.0)  # 던지지 않는다

    def test_exposed_on_metrics_endpoint(self):
        frame_stage.record(1.0, 1.1, 1.3)
        body = TestClient(app).get("/metrics").text

        self.assertIn('shadowfit_ai_frame_stage_seconds_bucket{le="0.1",stage="infer"}', body)


class SlowFrameLog(unittest.TestCase):
    def test_off_by_default(self):
        """«몇 ms 부터 느린가» 는 미확정이라 기본은 끈다 — 근거 없는 기준값을 박지 않는다."""
        self.assertEqual(settings.FRAME_SLOW_LOG_MS, 0.0)

    def test_logs_only_frames_over_the_threshold(self):
        with mock.patch.object(settings, "FRAME_SLOW_LOG_MS", 100.0):
            with self.assertLogs(frame_stage.logger, level=logging.WARNING) as cm:
                frame_stage.record(0.0, 0.05, 0.25)  # total 250ms > 100ms
            self.assertIn("느린 프레임", cm.output[0])
            self.assertIn("infer=200.0ms", cm.output[0])

            with self.assertNoLogs(frame_stage.logger, level=logging.WARNING):
                frame_stage.record(0.0, 0.01, 0.03)  # total 30ms

    def test_slow_log_carries_the_correlation_id(self):
        """cid 가 로그 레코드에 실려야 Spring 로그와 이어진다(라벨이 아니라 로그로 붙이는 이유)."""
        from app.grpc import correlation

        correlation.install_log_record_factory()
        token = correlation._correlation_id.set("ai-http-test-cid")
        try:
            with mock.patch.object(settings, "FRAME_SLOW_LOG_MS", 1.0):
                with self.assertLogs(frame_stage.logger, level=logging.WARNING) as cm:
                    frame_stage.record(0.0, 0.0, 0.5)
        finally:
            correlation._correlation_id.reset(token)

        self.assertEqual(cm.records[0].cid, "ai-http-test-cid")


class ArrivalStamp(unittest.TestCase):
    def test_outermost_middleware_stamps_arrival(self):
        """가장 바깥 미들웨어가 도착 시각을 찍어, 안쪽(핸들러 쪽)에서 읽힌다."""
        seen: dict[str, float | None] = {}

        async def inner(scope, receive, send):
            seen["arrival"] = frame_stage._arrival.get()

        async def run():
            await CorrelationHttpMiddleware(inner)({"type": "http"}, None, None)

        asyncio.run(run())

        self.assertIsNotNone(seen["arrival"], "도착 시각이 안 찍히면 wait 구간이 영영 비어 있다")

    def test_non_http_scope_is_untouched(self):
        called = {}

        async def inner(scope, receive, send):
            called["scope"] = scope["type"]

        async def run():
            await CorrelationHttpMiddleware(inner)({"type": "lifespan"}, None, None)

        asyncio.run(run())
        self.assertEqual(called["scope"], "lifespan")


class DefaultGaugesAreBound(unittest.TestCase):
    """🔴 회귀: 게이지 바인딩이 `FRAME_PATH_METRICS` 블록 안에 있어서, 계측이 꺼진 기본 구성에서는
    세션 수·콜백 풀 게이지가 **0 으로 고정**됐다. 기존 테스트는 «이름이 보이는가» 만 봐서 못 잡았다."""

    def test_active_sessions_follows_the_registry_with_default_settings(self):
        self.assertFalse(settings.FRAME_PATH_METRICS, "이 테스트는 계측 OFF(기본) 전제다")
        registry = get_registry()
        before = registry.active_count()
        registry.create(session_id=987654321, exercise_id=1, reference_angles=[[1.0]])
        try:
            value = metrics.REGISTRY.get_sample_value("shadowfit_ai_active_sessions")
        finally:
            registry.remove(987654321)

        self.assertEqual(value, before + 1, "세션을 만들어도 게이지가 안 오르면 바인딩이 빠진 것이다")

    def test_callback_pool_gauges_are_bound_with_default_settings(self):
        from app.grpc.exercise_servicer import get_complete_callback_pool

        pool = get_complete_callback_pool()
        release = threading.Event()
        started = threading.Event()

        def blocker() -> None:
            started.set()
            release.wait(timeout=10)

        base = pool.in_flight()
        pool.submit(blocker)
        try:
            self.assertTrue(started.wait(timeout=5), "풀이 작업을 못 집었다")
            in_flight = metrics.REGISTRY.get_sample_value("shadowfit_ai_complete_callback_in_flight")
        finally:
            release.set()

        self.assertEqual(in_flight, base + 1, "풀 게이지가 풀에 안 붙어 있다 — 0 으로 고정된다")


if __name__ == "__main__":
    unittest.main()
