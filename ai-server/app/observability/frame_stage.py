"""프레임 경로 구간 시간을 «상시» 기록한다 — 히스토그램 + (선택) 느린 프레임 로그.

결함 ④(`docs/decisions/architecture-review-2026-08-11.md`): 병목이 AI 인데 AI 구간을 못 본다.
`frame_path.py` 는 같은 구간을 더 잘게 재지만 기본이 꺼져 있고(요청당 락 4회 — 재려는 대상과
같은 자원인 GIL 을 쓴다) 스냅샷 JSON 이라 시계열이 안 된다. 이 모듈은 반대편이다 —
**항상 켜 둬도 되는 만큼만** 한다.

비용: 요청당 `perf_counter()` 4번 + 컨텍스트 변수 1번 + 히스토그램 `observe` 3번.
락은 `prometheus_client` 가 지표마다 하나씩 잡는 것이 전부다.

## 구간

    wait   요청 도착(가장 바깥 미들웨어) → 워커 진입   루프 + 스레드풀 대기
    lease  워커 진입 → 검출기 획득                     디코드 + 풀·세션 락 대기
    infer  검출기 획득 → 추론 완료                     MediaPipe process()

## correlation id 와의 연결

Prometheus 라벨에 cid 를 넣으면 카디널리티가 터진다. 그래서 cid 는 **느린 프레임 로그**로 붙인다 —
`FRAME_SLOW_LOG_MS` 를 넘긴 프레임만 구간 내역과 함께 WARNING 한 줄을 남기고, `cid` 는 로그
레코드 팩토리가 자동으로 붙인다(`grpc/correlation.py`). 그 cid 로 Spring 로그와 이어진다.
기본 0 = 끔 — «몇 ms 부터 느린가» 는 `slo-baseline.md` 가 미확정으로 두고 있어 여기서 정하지 않는다.
"""

from __future__ import annotations

import contextvars
import logging
import time

from app.config import settings
from app.observability import metrics

logger = logging.getLogger(__name__)

# 요청이 가장 바깥 미들웨어에 닿은 시각. 핸들러는 threadpool 의 **다른 스레드**지만 anyio 가
# 컨텍스트를 복사해 넘기므로 읽힌다(frame_path._current 와 같은 방식). 이 값은 읽기만 하니
# frame_path 처럼 «변경해서 되돌려받는» 객체가 필요 없다.
_arrival: contextvars.ContextVar[float | None] = contextvars.ContextVar(
    "frame_arrival", default=None
)


def stamp_arrival() -> None:
    """가장 바깥 미들웨어가 요청마다 한 번 부른다."""
    _arrival.set(time.perf_counter())


def record(t_handler_in: float, t_leased: float, t_inferred: float) -> None:
    """한 프레임의 구간을 기록한다. 계측이 프레임 처리를 깨뜨리면 안 되므로 절대 던지지 않는다.

    `t_*` 는 전부 `time.perf_counter()` 값이다. 도착 시각이 없으면(미들웨어를 안 거친 호출 —
    단위 테스트 등) `wait` 만 건너뛴다. 0 으로 채우면 «기다림 없음» 으로 읽히기 때문이다.
    """
    try:
        arrived = _arrival.get()
        wait = None if arrived is None else max(0.0, t_handler_in - arrived)
        lease = max(0.0, t_leased - t_handler_in)
        infer = max(0.0, t_inferred - t_leased)

        if wait is not None:
            metrics.observe_frame_stage("wait", wait)
        metrics.observe_frame_stage("lease", lease)
        metrics.observe_frame_stage("infer", infer)

        slow_ms = settings.FRAME_SLOW_LOG_MS
        if slow_ms > 0:
            total_ms = ((wait or 0.0) + lease + infer) * 1000.0
            if total_ms > slow_ms:
                logger.warning(
                    "느린 프레임 total=%.1fms (wait=%s lease=%.1fms infer=%.1fms) 임계=%.0fms",
                    total_ms,
                    "n/a" if wait is None else f"{wait * 1000.0:.1f}ms",
                    lease * 1000.0,
                    infer * 1000.0,
                    slow_ms,
                )
    except Exception:  # pragma: no cover - 계측이 본 경로를 깨뜨리지 않는다
        pass
