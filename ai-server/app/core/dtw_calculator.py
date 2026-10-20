"""DTW-based sync helpers plus frontend-facing cue classification."""

import math

import numpy as np
from dtaidistance import dtw

from app.config import settings
from app.models.sync import SyncHapticCue, SyncVisualCue
from app.utils.constants import DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN


def _asymmetric_mean_abs(ref: np.ndarray, user: np.ndarray, max_skip: int,
                         open_begin: float = 0.25, open_end: float = 0.25) -> float:
    """비대칭 DTW — **사용자 프레임마다 기준 프레임을 정확히 하나** 단조 대응시키고, 대응 쌍의
    |각도 차| (관절 평균)를 사용자 프레임 수로 평균한 값(도).

    왜 대칭 DTW(dtaidistance.dtw.distance) 가 아닌가(2026-10-20 실촬영):
      - 그 값은 경로를 따라 더한 **제곱합의 제곱근**이라 프레임 수에 따라 달라졌다 — 같은 영상 안에서도
        13프레임 반복이 87점, 5프레임 반복이 80점. 자세가 아니라 반복의 길이가 점수를 정했다.
      - 기준은 ~12fps, 사용자는 3fps 라 대칭 경로는 기준의 모든 프레임을 지나야 해서 빠른 반복일수록
        «건너뛴 기준 프레임» 만큼 거리가 쌓였다. 비대칭은 기준을 촘촘한 곡선으로 보고 사용자 프레임이
        그 위의 가장 가까운 점을 찾아가므로 속도 차이에 둔감하다(frontend lib/motion/dtw.ts 와 같은 설계).
    open_begin/open_end: 사용자 반복의 앞뒤 여유가 기준과 달라도 되도록 기준의 앞·뒤 25% 안에서 시작·종료를 허용.
    """
    n, m = len(user), len(ref)
    cost = np.mean(np.abs(user[:, None, :] - ref[None, :, :]), axis=2)  # n × m, 도
    D = np.full((n, m), np.inf)
    j_begin = int(open_begin * (m - 1))
    D[0, : j_begin + 1] = cost[0, : j_begin + 1]
    for i in range(1, n):
        prev = D[i - 1]
        for j in range(m):
            lo = max(0, j - max_skip)
            best = prev[lo : j + 1].min()
            if np.isfinite(best):
                D[i, j] = cost[i, j] + best
    j_end_min = int(np.ceil((1 - open_end) * (m - 1)))
    tail = D[n - 1, j_end_min:]
    end = tail.min() if tail.size else D[n - 1].min()
    if not np.isfinite(end):
        # max_skip 이 너무 작아 끝에 닿지 못한 경우 — 제한 없이 다시 (기준 길이만큼 건너뛸 수 있게)
        return _asymmetric_mean_abs(ref, user, max_skip=m, open_begin=open_begin, open_end=open_end) if max_skip < m else float(cost.min(axis=1).mean())
    return float(end / n)


def compute_dtw_distance(
    seq_ref: list[list[float]],
    seq_user: list[list[float]],
) -> float:
    """기준 ↔ 사용자 각도 시퀀스의 거리(도) — 비대칭 DTW 대응 쌍의 평균 |각도 차|.

    프레임 수·재생 속도에 무관한 «자세가 평균 몇 도 다른가» 다. 0 = 같은 자세.
    """
    ref = np.array(seq_ref, dtype=np.float64)
    user = np.array(seq_user, dtype=np.float64)
    if ref.ndim != 2 or user.ndim != 2 or ref.shape[1] != user.shape[1]:
        raise ValueError("각도 시퀀스의 모양이 다르다")
    if not len(ref) or not len(user):
        raise ValueError("빈 시퀀스")
    # 기준(≈12fps)이 사용자(3fps)보다 촘촘하므로, 사용자 한 프레임 사이에 기준이 건너뛸 수 있는 폭은
    # 샘플링 비 × 2 정도면 충분하다. 설정값(DTW_WINDOW_SIZE=10)을 그 상한으로 쓴다.
    return _asymmetric_mean_abs(ref, user, max_skip=max(1, settings.DTW_WINDOW_SIZE))


def refine_min(values: list[float] | np.ndarray, max_beyond: float = 25.0) -> float:
    """표본 사이에 떨어진 최솟값 추정 — 최소 표본과 양옆 이웃 세 점을 지나는 포물선의 꼭짓점(등간격 가정).

    3fps 로 받으면 가장 깊은 순간이 두 프레임 사이에 있기 일쑤라 표본 최솟값만 쓰면 깊이를 늘 얕게 잰다.
    꼭짓점이 이웃 구간 밖이거나 표본값보다 크면 표본값, 표본값보다 max_beyond 이상 낮으면 그만큼만 인정.
    """
    v = np.asarray(values, dtype=np.float64)
    if v.size == 0:
        return float("nan")
    i = int(v.argmin())
    if i == 0 or i == v.size - 1:
        return float(v[i])
    y0, y1, y2 = v[i - 1], v[i], v[i + 1]
    a = (y0 + y2 - 2 * y1) / 2.0
    if a <= 0:
        return float(y1)
    xv = (y0 - y2) / (4.0 * a)
    if not -1.0 <= xv <= 1.0:
        return float(y1)
    yv = y1 - a * xv * xv
    return float(min(y1, max(yv, y1 - max_beyond)))


# 최저점 자세 항의 허용 오차(도) — 단안 2D 각도는 촬영 방향에 따라 몇 도씩 흔들리므로 그 안쪽은 «같다».
_KNEE_TOLERANCE_DEG = 8.0
_HIP_TOLERANCE_DEG = 10.0
# 거리 합성 가중치: 최저점 무릎 0.6 · 최저점 고관절 0.2 · 궤적(프레임 평균) 0.2
_W_KNEE, _W_HIP, _W_TRAJ = 0.6, 0.2, 0.2


def _bottom_terms(seq_ref: np.ndarray, seq_user: np.ndarray) -> tuple[float, float]:
    """(최저 무릎각이 기준보다 큰 만큼 − 허용, 최저 고관절각이 기준보다 큰 만큼 − 허용), 음수는 0.

    **한쪽 방향만 감점한다** — 기준은 «맞춰야 할 과녁» 이 아니라 «넘어야 할 바닥» 이다. 기준보다 더 깊게 앉은
    (각도가 더 작은) 반복을 «다르다» 고 깎으면 자세가 좋은 사람이 손해를 본다(2026-10-20). 열 배치는
    EXERCISE_ANGLES['squat'] 순서(무릎 L·R, 고관절 L·R).
    """
    if seq_ref.shape[1] < 4:
        return 0.0, 0.0
    knee_ref = refine_min((seq_ref[:, 0] + seq_ref[:, 1]) / 2)
    knee_user = refine_min((seq_user[:, 0] + seq_user[:, 1]) / 2)
    hip_ref = refine_min((seq_ref[:, 2] + seq_ref[:, 3]) / 2)
    hip_user = refine_min((seq_user[:, 2] + seq_user[:, 3]) / 2)
    return (max(0.0, (knee_user - knee_ref) - _KNEE_TOLERANCE_DEG),
            max(0.0, (hip_user - hip_ref) - _HIP_TOLERANCE_DEG))


def compute_sync_distance(seq_ref: list[list[float]], seq_user: list[list[float]]) -> float:
    """싱크로율에 들어가는 합성 거리(도) = 0.6·최저점 무릎 차 + 0.2·최저점 고관절 차 + 0.2·궤적 평균 차.

    궤적(프레임 평균)만 쓰면 서 있는·내려가는 구간이 다 비슷해서 **최저점 깊이 차이가 묻혔다**(2026-10-20 실촬영:
    무릎 최소 50~77° 영상과 77~93° 영상이 같은 점수). 반복에서 자세가 갈리는 곳은 최저점이므로 그 항을 주축으로 둔다.
    """
    ref = np.array(seq_ref, dtype=np.float64)
    user = np.array(seq_user, dtype=np.float64)
    traj = compute_dtw_distance(seq_ref, seq_user)
    knee, hip = _bottom_terms(ref, user)
    return float(_W_KNEE * knee + _W_HIP * hip + _W_TRAJ * traj)


def compute_sync_rate(
    seq_ref: list[list[float]],
    seq_user: list[list[float]],
) -> float:
    """합성 거리(도) → 0~100 싱크로율: 100 × exp(−거리 / SYNC_RATE_TAU_DEG).

    τ 는 실촬영으로 맞춘 값(config 참고): 최저점이 기준과 8° 안쪽이면 90 안팎, 20° 얕으면 70 안팎, 35° 얕으면 50 안팎.
    """
    distance = compute_sync_distance(seq_ref, seq_user)
    rate = 100.0 * np.exp(-distance / settings.SYNC_RATE_TAU_DEG)
    return round(float(np.clip(rate, 0.0, 100.0)), 2)


def resolve_sync_thresholds(
    good: float | None, warn: float | None
) -> tuple[float, float]:
    """싱크로율 임계값 한 쌍(«양호» good · «주의» warn)을 쓸 수 있는 값으로 고른다 (2026-10-21).

    - 각 값이 없거나(None · proto3 의 0.0) 범위 밖(≤0, >100, 유한하지 않음)이면 그 값만 기본값으로.
    - 그렇게 고른 뒤에도 warn ≥ good 이면 경계가 뒤집힌 것이라 **둘 다** 기본값으로.

    로그는 남기지 않는다 — 요청을 받는 자리(exercise_servicer)가 원래 값과 비교해 경고한다.
    """
    def _valid(value: float | None) -> bool:
        return value is not None and math.isfinite(value) and 0.0 < value <= 100.0

    g = float(good) if _valid(good) else DEFAULT_SYNC_GOOD
    w = float(warn) if _valid(warn) else DEFAULT_SYNC_WARN
    if w >= g:
        return DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN
    return g, w


def classify_sync_visual_cue(
    sync_rate: float,
    good: float = DEFAULT_SYNC_GOOD,
    warn: float = DEFAULT_SYNC_WARN,
) -> SyncVisualCue:
    """Map sync rate to frontend visual guidance.

    good/warn: 운동별 임계값 한 쌍(2026-10-21). good 이상 초록 · warn 이상 주황 · 그 아래 빨강 —
    실시간 rep 판정(squat_analyzer._summarize_rep)의 세 문구와 같은 경계다. 예전엔 70/40 고정이었다.
    0 이나 말이 안 되는 값은 resolve_sync_thresholds 가 기본값(80/60)으로 바꾼다.
    """
    good, warn = resolve_sync_thresholds(good, warn)
    if sync_rate >= good:
        return SyncVisualCue(
            zone="green",
            color="green",
            flashing=False,
            animation="steady",
            message="싱크로율 양호",
        )
    if sync_rate >= warn:
        return SyncVisualCue(
            zone="orange",
            color="orange",
            flashing=False,
            animation="steady",
            message="자세 보정 필요",
        )
    return SyncVisualCue(
        zone="red",
        color="red",
        flashing=True,
        animation="twinkle_flash",
        message="즉시 자세 수정 필요",
    )


def classify_sync_haptic_cue(
    sync_rate: float,
    good: float = DEFAULT_SYNC_GOOD,
    warn: float = DEFAULT_SYNC_WARN,
) -> SyncHapticCue:
    """Map sync rate to frontend haptic guidance. 경계는 classify_sync_visual_cue 와 같다."""
    good, warn = resolve_sync_thresholds(good, warn)
    if sync_rate < warn:
        return SyncHapticCue(
            enabled=True,
            pattern="warning_repeat",
            message=f"{warn:g}% 미만 진입 시 강한 경고 진동",
        )
    if sync_rate < good:
        return SyncHapticCue(
            enabled=True,
            pattern="light_repeat",
            message=f"{warn:g}~{good:g}% 구간 유지 시 가벼운 반복 진동",
        )
    return SyncHapticCue(
        enabled=False,
        pattern="off",
        message="진동 없음",
    )
