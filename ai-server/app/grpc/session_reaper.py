"""방치된 세션 정리 — Spring 의 StopAnalysis 가 영영 안 오는 경우의 **안전망**.

[왜 필요한가] 세션 상태는 이 프로세스의 메모리에만 있고, 지우는 곳은 `StopAnalysis` 하나뿐이었다. Spring 이 그 통보를
못 보내면(아웃박스 소진, Spring 장애) 상태와 검출기(약 100MB)가 재기동 전까지 남는다. 검출기 풀 크기가 곧 동시 세션
상한이라, 누수가 쌓이면 신규 세션이 `RESOURCE_EXHAUSTED` 로 막힌다.

[정상 경로가 아니다] 평소에는 Spring 의 타임아웃 스케줄러가 먼저 세션을 걷고 `StopAnalysis` 를 보낸다. 여기는 그게
안 왔을 때만 일한다 — 그래서 **Spring 의 최악 타임아웃보다 충분히 길어야 한다**(아래 기본값의 근거).

[꺼낸 세션에 결과를 만들지 않는다] 정리된 세션은 `CompleteAnalysis` 를 보내지 않는다. 이미 Spring 이 FAILED 로 닫았거나
닫을 것이고, 방치된 세션에는 지킬 결과가 없다. 정리 뒤에 `StopAnalysis` 가 늦게 오면 «세션 없음»(success=False) 으로
답하는데, `registry.remove()` 와 달리 «최근 종료» 기록을 **남기지 않는다** — 정상 종료로 위장하지 않고 Spring 의
`session-missing` 지표에 그대로 보이게 하려는 것이다.
"""

from __future__ import annotations

import logging
import threading

from app.grpc.session_state import SessionStateRegistry, get_registry
from app.observability import metrics

logger = logging.getLogger(__name__)


def reap_once(
    registry: SessionStateRegistry,
    pool,
    max_idle_sec: float,
    now: float | None = None,
) -> list[int]:
    """한 번 훑어 방치된 세션의 상태를 지우고 검출기를 반납한다. 정리한 세션 id 를 돌려준다.

    검출기 반납이 한 세션에서 실패해도 나머지 세션은 계속 정리한다 — 하나가 막으면 안전망 전체가 멎는다.
    """
    reaped = registry.reap_idle(max_idle_sec, now=now)
    for session_id in reaped:
        try:
            pool.release(session_id)
        except Exception:
            logger.exception("방치 세션 %s 의 검출기 반납 실패 — 상태는 이미 지웠다", session_id)
    if reaped:
        metrics.record_sessions_reaped(len(reaped))
        logger.warning(
            "방치된 세션 %d 개를 정리했다 (유입 없음 %.0f초 초과): %s — Spring 의 StopAnalysis 가 오지 않았다는 뜻이다",
            len(reaped), max_idle_sec, reaped,
        )
    return reaped


def run_reaper(stop: threading.Event, interval_sec: float, max_idle_sec: float) -> None:
    """주기적으로 `reap_once` 를 돈다 — 데몬 스레드에서 실행한다. `stop` 이 set 되면 끝난다."""
    from app.core.mediapipe_detector import get_pool

    logger.info("방치 세션 정리 시작 — 주기 %.0f초, 유입 없음 상한 %.0f초", interval_sec, max_idle_sec)
    while not stop.wait(interval_sec):
        try:
            reap_once(get_registry(), get_pool(), max_idle_sec)
        except Exception:
            # 안전망이 죽으면 안 된다 — 다음 주기에 다시 한다. 풀이 아직 안 만들어진 경우(RuntimeError)도 여기로 온다.
            logger.exception("방치 세션 정리 중 오류 — 다음 주기에 다시 한다")
