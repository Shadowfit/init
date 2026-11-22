"""ai-server 의 Prometheus 지표 (#151).

관측 스택이 세 서비스 중 **Spring 하나만** 덮고 있었다. MySQL 은 mysqld_exporter 로 닫혔고,
남은 것이 여기다 — 이 모듈이 그 자리를 연다.

🔴 **왜 «계측이 있다» 로 부족했나.** #384 가 프레임 경로 계측을 붙였지만 그 형태는
`frame_path.snapshot()` 딕셔너리 + `GET /frame-path` 다. 그건 **스냅샷**이고 스택이 긁는 것은
**시계열**이다. 이 이슈 자신이 mysqld_exporter 를 들일 때 쓴 잣대가 정확히 그것이었다 —
*"스냅샷과 시계열을 같은 것으로 본 판정이다"*. 같은 잣대를 AI 에 대면 아직 안 덮인 상태였다.

## 무엇을 세나 — 지금은 «못 세던 것» 부터

`shadowfit_ai_spring_callback_total{rpc, outcome}` 하나가 이 모듈의 본체다.

AI → Spring 콜백은 **두 겹의 재시도** 중 바깥 겹이다(안쪽은 Spring 의 데드락 재시도, #276).
두 겹이 다 소진되면 **rep 하나가 사라지는데**, 지금까지 그 사건은 ERROR 로그 한 줄로만 남아
«얼마나 자주 일어나나» 에 답이 없었다(#276 ③ 이 남긴 조각). outcome 을 갈라 세면 그 답이 생긴다:

    ok        성공 (재시도를 거쳤든 아니든)
    retried   다시 던졌다 (횟수만큼 오른다)
    rejected  영구 실패라 안 던졌다 (NOT_FOUND 등 — 세션이 사라진 뒤)
    exhausted 재시도 상한까지 갔는데 실패 ← 🔴 이 칸이 «유실» 이다

⚠️ **임계값은 여기서 정하지 않는다.** 「몇 건부터 나쁜가」는 `docs/decisions/slo-baseline.md`
가 미확정으로 두고 있고, 근거 없는 숫자를 계측 코드에 박지 않는다. 이 모듈이 답하는 것은
**세는 수단**까지다.

## 프로세스 지표는 공짜로 따라온다

`prometheus_client` 기본 컬렉터가 CPU·메모리·GC·FD 를 내보낸다. 이 이슈가 «아픈 건 아는데
어디가 아픈지 모른다» 로 적은 자리 — 서킷이 열렸을 때 AI 가 메모리인지 CPU 인지 —
의 절반이 그것으로 열린다.
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, generate_latest
from prometheus_client import REGISTRY as DEFAULT_REGISTRY

# 기본 레지스트리를 쓴다 — 프로세스 지표(CPU·메모리·GC)가 거기 붙어 있고, 그게 이 이슈가
# 원하던 절반이다. 별도 레지스트리를 만들면 그것들이 안 나온다.
REGISTRY: CollectorRegistry = DEFAULT_REGISTRY

CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

# rpc 라벨을 두는 이유: 세 콜백의 성격이 다르다. SavePoseDataBatch 는 rep 마다 오고
# CompleteAnalysis 는 세션당 한 번이라, 같은 «실패 1건» 의 무게가 다르다.
spring_callback_total = Counter(
    "shadowfit_ai_spring_callback",
    "AI → Spring 콜백 결과. exhausted 는 «두 겹이 다 소진돼 유실» 을 뜻한다 (#151, #276 ③)",
    ["rpc", "outcome"],
)

# 살아 있는 세션 수. 게이지를 «지금 값을 물어보는» 방식으로 두는 이유는, 세션 생성/삭제 자리마다
# inc/dec 를 심으면 한 자리만 빠져도 조용히 어긋나기 때문이다 — 레지스트리가 진실이다.
active_sessions = Gauge(
    "shadowfit_ai_active_sessions",
    "in-memory session_state 에 살아 있는 세션 수 (#151)",
)


# 방치로 AI 가 정리한 세션 수. 0 이 아니면 Spring 의 StopAnalysis 가 오지 않은 세션이 있었다는 뜻이다 —
# 정상 운영에서는 Spring 의 타임아웃 스케줄러가 먼저 걷으므로 0 이어야 한다.
sessions_reaped_total = Counter(
    "shadowfit_ai_sessions_reaped",
    "방치(유입 없음 SESSION_IDLE_TTL_SEC 초과)로 AI 가 직접 정리한 세션 수 — Spring 의 StopAnalysis 가 안 온 세션",
)


def record_sessions_reaped(count: int) -> None:
    """정리한 세션 수를 센다. 지표 때문에 정리가 죽으면 안 되므로 절대 던지지 않는다."""
    try:
        sessions_reaped_total.inc(count)
    except Exception:  # pragma: no cover - 계측이 본 경로를 깨뜨리지 않는다
        pass


def record_callback(rpc: str, outcome: str) -> None:
    """콜백 결과를 센다. 지표 때문에 콜백이 죽으면 안 되므로 절대 던지지 않는다."""
    try:
        spring_callback_total.labels(rpc=rpc, outcome=outcome).inc()
    except Exception:  # pragma: no cover - 계측이 본 경로를 깨뜨리지 않는다
        pass


def bind_active_sessions(count_fn) -> None:
    """세션 수를 «스크레이프 시점에» 읽도록 붙인다."""
    active_sessions.set_function(count_fn)


# CompleteAnalysis «느린 재시도» 대기 건수 (#799). 0 이 아닌 상태가 오래가면 Spring 이 계속 안 받는 것이다.
# 이 값이 줄지 않고 outcome="exhausted" 가 오르면 결과가 유실되고 있다.
complete_callback_late_pending = Gauge(
    "shadowfit_ai_complete_callback_late_pending",
    "CompleteAnalysis 즉시 재시도가 끝나 느린 재시도(타이머)를 기다리는 결과 수 (#799)",
)


def late_retry_pending(delta: int) -> None:
    """느린 재시도 대기 건수를 올리거나(+1) 내린다(-1). 지표 때문에 콜백이 죽으면 안 되므로 절대 던지지 않는다."""
    try:
        complete_callback_late_pending.inc(delta)
    except Exception:  # pragma: no cover - 계측이 본 경로를 깨뜨리지 않는다
        pass


# CompleteAnalysis 콜백 풀 상태 (#614). 스레드 수는 상한이 있고 큐는 없다 — «쌓이고 있다» 는
# 이 게이지로만 보인다. pending 이 workers 를 계속 넘으면 Spring 쪽이 느린 것이다.
complete_callback_pending = Gauge(
    "shadowfit_ai_complete_callback_pending",
    "CompleteAnalysis 콜백 풀 큐에서 기다리는 작업 수 (#614)",
)
complete_callback_in_flight = Gauge(
    "shadowfit_ai_complete_callback_in_flight",
    "CompleteAnalysis 콜백 풀에서 실행 중인 작업 수 (≤ COMPLETE_CALLBACK_WORKERS, #614)",
)


def bind_complete_callback_pool(pool) -> None:
    """콜백 풀의 큐 깊이·실행 수를 «스크레이프 시점에» 읽도록 붙인다."""
    complete_callback_pending.set_function(pool.pending)
    complete_callback_in_flight.set_function(pool.in_flight)


# 프레임 경로 구간 시간 (결함 ④ — «병목이 AI 인데 AI 구간을 못 본다»).
#
# `frame_path.py` 도 같은 구간을 재지만 **기본이 꺼져 있고**(요청당 락 4회 — 재려는 대상과 같은
# 자원인 GIL 을 쓴다) 스냅샷 JSON 으로만 나온다. 이 히스토그램은 **상시 켜 두는 쪽**이다 —
# 요청당 타임스탬프 4개와 `observe` 3번이 전부이고, 스크레이프가 시계열로 긁는다.
#
#   wait   요청 도착 → 워커 진입   이벤트 루프 + 스레드풀 대기. «워커에 못 실린다» 가 여기 쌓인다
#   lease  워커 진입 → 검출기 획득  디코드 + 검출기 리스. 풀·세션 락 대기가 여기 쌓인다
#   infer  검출기 획득 → 추론 완료  MediaPipe process()
#
# ⚠️ 두 계측은 **같은 시계(perf_counter)지만 같은 값이 아니다.** `lease` 는 여기서 디코드를
#    포함한다(frame_path 는 디코드와 리스를 가른다) — 대조할 때 구간 정의부터 맞출 것.
# ⚠️ 버킷은 «해상도 선택» 이지 «판정선» 이 아니다. 몇 ms 부터 나쁜가는 slo-baseline.md 가
#    미확정으로 두고 있고, 여기서 정하지 않는다.
FRAME_STAGES = ("wait", "lease", "infer")

frame_stage_seconds = Histogram(
    "shadowfit_ai_frame_stage_seconds",
    "POST /pose 한 프레임의 구간별 시간 (wait=워커 대기 · lease=디코드+검출기 획득 · infer=추론)",
    ["stage"],
    buckets=(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0),
)


# 자식 지표를 미리 만들어 둔다 — `labels()` 는 호출마다 라벨 검증·해시·락을 타서 `observe` 자체보다
# 비쌌다(마이크로벤치: labels()+observe 16µs ↔ 캐시한 자식의 observe 6.7µs, 붐비는 박스 기준).
# 이 경로는 프레임마다 3번 불리므로 상시 ON 계측의 비용 대부분이 여기였다.
_stage_children = {s: frame_stage_seconds.labels(stage=s) for s in FRAME_STAGES}


def observe_frame_stage(stage: str, seconds: float) -> None:
    """구간 시간을 기록한다. 지표 때문에 프레임 처리가 죽으면 안 되므로 절대 던지지 않는다.

    `FRAME_STAGES` 밖의 이름은 조용히 버린다 — 라벨 값이 늘어 카디널리티가 새는 것보다 낫다.
    """
    try:
        _stage_children[stage].observe(seconds)
    except Exception:  # pragma: no cover - 계측이 본 경로를 깨뜨리지 않는다
        pass


def render() -> bytes:
    """Prometheus 텍스트 포맷."""
    return generate_latest(REGISTRY)
