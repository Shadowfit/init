"""DTW-based sync helpers plus frontend-facing cue classification."""

import math
from collections.abc import Sequence

import numpy as np
from dtaidistance import dtw
from numpy.lib.stride_tricks import sliding_window_view

from app.config import settings
from app.models.sync import SyncHapticCue, SyncVisualCue
from app.utils.constants import DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN

# 사용자 각도열을 시간축으로 펴는 간격(초) — 초당 12점. 기준(VIDEO_PROCESS_FPS=10)보다 살짝 촘촘하게 둔다.
DENSIFY_STEP_SEC = 1.0 / 12.0
# 시각을 모를 때 가정하는 사용자 프레임 간격(초) — 앱 전송 주기 ~3fps(exercise.tsx intervalMs=330).
DEFAULT_USER_FRAME_SEC = 1.0 / 3.0
# 편 결과 점 수의 상한 — 정상 반복(버퍼 상한 MAX_REP_FRAMES=60 ≈ 20초 → ~240점)과는 무관하고,
# 기준점이 다른 시각이 섞여 들어와 구간이 비정상적으로 길어졌을 때 수만 점이 생기는 것만 막는다.
DENSIFY_MAX_POINTS = 1024


def densify(seq: Sequence | np.ndarray, times: Sequence[float] | np.ndarray | None = None,
            step_sec: float = DENSIFY_STEP_SEC, max_points: int = DENSIFY_MAX_POINTS) -> np.ndarray:
    """각도열의 모든 채널을 시간축으로 선형 보간해 첫 시각~마지막 시각을 step_sec 간격 격자로 다시 뽑는다
    (마지막 시각은 정확히 포함).

    왜(2026-10-21 실측): 궤적 항은 «사용자 점마다» 비용을 평균하므로, 3fps 로 받은 반복은 **어느 순간이
    우연히 찍혔는지**가 점수를 정했다 — 같은 깊이의 반복이 3프레임이면 73, 5프레임이면 90. 프레임 사이를
    직선으로 메워 평균을 «찍힌 프레임» 이 아니라 «연속 동작» 위에서 내면 그 편향이 사라진다.

    times: 프레임별 시각(초). None 이거나 길이가 다르거나 유한한 순증가가 아니면 DEFAULT_USER_FRAME_SEC
        등간격으로 본다(어긋난 시각 한두 개를 고쳐 쓰지 않고 통째로 등간격 — 앱이 거의 등간격으로 보낸다).
    max_points: 격자가 이보다 길어지면 간격을 넓혀 이 개수에 맞춘다.
    길이 2 미만은 그대로(배열로만 바꿔) 돌려준다.
    """
    arr = np.asarray(seq, dtype=np.float64)
    n = len(arr)
    if n < 2:
        return arr
    t = None
    if times is not None:
        t = np.asarray(times, dtype=np.float64).reshape(-1)
        if t.size != n or not np.all(np.isfinite(t)) or not np.all(np.diff(t) > 0):
            t = None
    if t is None:
        t = np.arange(n, dtype=np.float64) * DEFAULT_USER_FRAME_SEC
    span = float(t[-1] - t[0])
    step = float(step_sec) if math.isfinite(step_sec) and step_sec > 0 else DENSIFY_STEP_SEC
    if max_points >= 2 and span / step + 1 > max_points:
        step = span / (max_points - 1)
    k = int(np.floor(span / step + 1e-9))
    grid = t[0] + np.arange(k + 1, dtype=np.float64) * step
    if t[-1] - grid[-1] > 1e-9:
        grid = np.append(grid, t[-1])
    else:
        grid[-1] = t[-1]
    # 격자점마다 왼쪽 표본 idx 와 오른쪽 표본 idx+1 사이의 비율 w 로 섞는다 — 채널 수와 무관하게 한 번에.
    idx = np.clip(np.searchsorted(t, grid, side="right") - 1, 0, n - 2)
    w = ((grid - t[idx]) / (t[idx + 1] - t[idx])).reshape((-1,) + (1,) * (arr.ndim - 1))
    return arr[idx] * (1.0 - w) + arr[idx + 1] * w


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

    점화식 D[i, j] = cost[i, j] + min(D[i-1, j-max_skip .. j]) 의 «창 최솟값» 을 행 단위로 벡터화했다
    (sliding_window_view, 창이 기준 전체를 덮으면 누적 최솟값). 사용자 열을 시간축으로 펴서(densify)
    행이 수백 개가 돼도 행마다 numpy 연산 한 번이다 — 결과는 예전 이중 파이썬 루프와 같고, 같은 편 입력에서
    10초 반복(121점)·기준 43프레임 7ms → 1.5ms, 20초(241점)·기준 100프레임 45ms → 4ms (2026-10-21, AI 이미지,
    새 쪽은 compute_sync_rate 전체 시간).
    """
    n, m = len(user), len(ref)
    cost = np.mean(np.abs(user[:, None, :] - ref[None, :, :]), axis=2)  # n × m, 도
    D = np.full((n, m), np.inf)
    j_begin = int(open_begin * (m - 1))
    D[0, : j_begin + 1] = cost[0, : j_begin + 1]
    pad = np.full(max_skip, np.inf) if max_skip < m - 1 else None
    for i in range(1, n):
        prev = D[i - 1]
        if pad is None:
            best = np.minimum.accumulate(prev)        # 창이 0..j 전체 — 누적 최솟값
        else:
            best = sliding_window_view(np.concatenate((pad, prev)), max_skip + 1).min(axis=1)
        D[i] = cost[i] + best                          # 닿을 수 없는 칸은 inf + 비용 = inf 로 남는다
    j_end_min = int(np.ceil((1 - open_end) * (m - 1)))
    tail = D[n - 1, j_end_min:]
    end = tail.min() if tail.size else D[n - 1].min()
    if not np.isfinite(end):
        # max_skip 이 너무 작아 끝에 닿지 못한 경우 — 제한 없이 다시 (기준 길이만큼 건너뛸 수 있게)
        return _asymmetric_mean_abs(ref, user, max_skip=m, open_begin=open_begin, open_end=open_end) if max_skip < m else float(cost.min(axis=1).mean())
    return float(end / n)


def _as_angle_arrays(seq_ref, seq_user) -> tuple[np.ndarray, np.ndarray]:
    ref = np.array(seq_ref, dtype=np.float64)
    user = np.array(seq_user, dtype=np.float64)
    if ref.ndim != 2 or user.ndim != 2 or ref.shape[1] != user.shape[1]:
        raise ValueError("각도 시퀀스의 모양이 다르다")
    if not len(ref) or not len(user):
        raise ValueError("빈 시퀀스")
    return ref, user


def _trajectory_distance(ref: np.ndarray, user: np.ndarray,
                         user_times: Sequence[float] | None) -> float:
    """궤적 항(도) — 사용자 각도를 채널마다 기준 최솟값에서 자르고(clamp), 시간축으로 편 뒤(densify) 비대칭 DTW.

    clamp(2026-10-21): 최저점 항은 한쪽 방향(기준보다 깊으면 감점 없음)인데 궤적 항만 양쪽이라, 시범보다 깊이
    앉은 반복이 5~8점을 잃었다. 기준 최솟값 아래로 내려간 부분은 «기준의 바닥에 닿았다» 로 본다. 채널은
    굴곡각(작을수록 깊다)이라고 가정한다 — 스쿼트 열(무릎 L·R, 고관절 L·R)이 그렇다.
    """
    clamped = np.maximum(user, ref.min(axis=0))
    # 편 사용자 열은 초당 ~12점, 기준은 ~10fps 라 사용자 한 점 사이에 기준이 1칸 안팎 움직인다. 설정값
    # (DTW_WINDOW_SIZE=10)은 사용자가 시범보다 10배 이상 빨라야 걸리는 상한이라 그대로 충분하다.
    return _asymmetric_mean_abs(ref, densify(clamped, user_times),
                                max_skip=max(1, settings.DTW_WINDOW_SIZE))


def compute_dtw_distance(
    seq_ref: list[list[float]],
    seq_user: list[list[float]],
    user_times: Sequence[float] | None = None,
) -> float:
    """기준 ↔ 사용자 각도 시퀀스의 거리(도) — 싱크로율 합성 거리에 들어가는 **궤적 항 그대로**.

    사용자 각도를 기준 최솟값에서 자르고 시간축으로 편 뒤 잰 비대칭 DTW 평균 |각도 차|(_trajectory_distance).
    프레임 수·재생 속도에 무관한 «자세가 평균 몇 도 다른가» 이고, 기준보다 깊은 부분은 차이로 치지 않는다.
    0 = 같은 자세. /sync 응답의 dtw_distance 가 이 값이다 — 같은 응답의 sync_rate 와 같은 궤적을 보도록
    2026-10-21 부터 편·자른 값을 낸다(예전엔 찍힌 프레임 그대로의 양방향 차이).

    user_times: 사용자 프레임별 시각(초). None 이면 ~3fps 등간격으로 본다(densify).
    """
    ref, user = _as_angle_arrays(seq_ref, seq_user)
    return _trajectory_distance(ref, user, user_times)


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
# 2026-10-21 제품 결정으로 조금 엄하게: 무릎 8→5°, 고관절 10→8° (τ 30→24 와 한 묶음, config 참고).
# 🔴 무릎 허용(5°)은 백엔드 backend/src/main/java/com/shadowfit/service/report/SquatDepthAnalysisCalculator.java 의
#    깊이 점수 목표(기준 최저 무릎각 + 5°)와 같은 값이다 — 둘은 반드시 같이 바꾼다. 안 그러면 실시간 싱크로율은
#    «기준만큼 앉았다» 고 하는데 리포트 깊이 점수는 «덜 앉았다» 고 하는(또는 그 반대) 반복이 생긴다.
_KNEE_TOLERANCE_DEG = 5.0
_HIP_TOLERANCE_DEG = 8.0
# 거리 합성 가중치: 최저점 무릎 0.6 · 최저점 고관절 0.2 · 궤적(시간축 평균) 0.2
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


def compute_sync_distance(seq_ref: list[list[float]], seq_user: list[list[float]],
                          user_times: Sequence[float] | None = None) -> float:
    """싱크로율에 들어가는 합성 거리(도) = 0.6·최저점 무릎 차 + 0.2·최저점 고관절 차 + 0.2·궤적 평균 차.

    궤적(프레임 평균)만 쓰면 서 있는·내려가는 구간이 다 비슷해서 **최저점 깊이 차이가 묻혔다**(2026-10-20 실촬영:
    무릎 최소 50~77° 영상과 77~93° 영상이 같은 점수). 반복에서 자세가 갈리는 곳은 최저점이므로 그 항을 주축으로 둔다.

    궤적 항의 두 편향을 2026-10-21 에 걷어냈다(_trajectory_distance, 실촬영 반복 재생으로 확인):
      ① 프레임 수 편향 — 평균을 «찍힌 사용자 프레임» 위에서 내서, 같은 품질의 반복이 4프레임(과도 프레임 1장
         포함)이면 78~84, 5~6프레임이면 86~92 였다. → 시각 기준으로 펴서(densify) 연속 동작 위에서 평균.
      ② 시범보다 깊으면 감점 — 최저점 항은 한쪽 방향인데 궤적 항만 양쪽이라 5~8점을 잃었다.
         → 사용자 각도를 채널마다 기준 최솟값에서 자른 뒤 비교.
    최저점 항은 자르지도 펴지도 않은 원래 사용자 값으로 잰다.

    user_times: 사용자 프레임별 시각(초, 순증가). None 이면 ~3fps 등간격으로 본다.
    """
    ref, user = _as_angle_arrays(seq_ref, seq_user)
    traj = _trajectory_distance(ref, user, user_times)
    knee, hip = _bottom_terms(ref, user)
    return float(_W_KNEE * knee + _W_HIP * hip + _W_TRAJ * traj)


def compute_sync_rate(
    seq_ref: list[list[float]],
    seq_user: list[list[float]],
    user_times: Sequence[float] | None = None,
) -> float:
    """합성 거리(도) → 0~100 싱크로율: 100 × exp(−거리 / SYNC_RATE_TAU_DEG).

    τ·허용 오차는 실촬영 반복으로 맞춘 값(2026-10-21, config 참고). 기준(3D 마네킹 스쿼트, 최저 무릎 71°·
    고관절 63°)에 대해: 깊고 바른 반복(무릎 48~66°) 89~95, 무릎 76~78° 81~87, 81° 76, 84° 69, 93° 47.
    예전 식은 깊은 반복이 프레임 수에 따라 78~92 로 흔들렸고 84° 77, 93° 58 이었다. 반복을 3/4/5프레임으로
    솎아도(최저점 프레임은 남김) 점수는 평균 2점 안팎만 움직인다 — 최저점 바로 옆 프레임이 빠지면 최저점 항의
    refine_min(등간격 가정)이 바뀌어 더 크게(실측 최대 ~10점) 움직일 수 있다.

    user_times: 사용자 프레임별 시각(초). None 이면 ~3fps 등간격으로 본다.
    """
    distance = compute_sync_distance(seq_ref, seq_user, user_times)
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
