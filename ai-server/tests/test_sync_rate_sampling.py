"""실시간 싱크로율(compute_sync_rate)이 «몇 프레임이 찍혔나» 와 «시범보다 깊었나» 에 흔들리지 않는지 (2026-10-21).

배경: 앱은 ~3fps 로 보내서 같은 깊이의 반복도 3프레임이면 73, 5프레임이면 90 을 받았다 — 궤적 항이 비용을
«찍힌 사용자 프레임» 위에서 평균했기 때문이다. 또 최저점 항은 한쪽 방향(기준보다 깊으면 감점 없음)인데 궤적 항만
양쪽이라 시범보다 깊이 앉으면 5~8점을 잃었다. 고친 뒤의 성질을 매끈한 합성 반복으로 고정한다:
  a) 같은 동작을 3·4·5·8·12프레임으로 찍어도(최저점 프레임 포함) 점수가 3점 안에서 같다
  b) 무릎 50° 까지 앉은 반복이 기준 깊이까지 앉은 같은 반복보다 낮지 않다
  c) 최저점이 기준보다 0·10·20·30° 얕아질수록 점수가 엄격히 내려간다
  d) densify 의 경계 사례
그리고 squat_analyzer 가 rep 의 실제 프레임 시각을 넘기는지(_rep_times).
"""
import math

import numpy as np
import pytest

from app.core import squat_analyzer
from app.core.dtw_calculator import (
    DEFAULT_USER_FRAME_SEC,
    DENSIFY_MAX_POINTS,
    compute_dtw_distance,
    compute_sync_rate,
    densify,
)
from app.core.squat_analyzer import StreamingSquatAnalyzer, _rep_times
from app.grpc.session_state import PerRepFrame, SessionState

STAND = 170.0
REF_KNEE, REF_HIP = 71.0, 63.0     # 실제 기준(3D 마네킹 스쿼트)의 최저 무릎·고관절과 비슷하게
REF_DURATION = 3.0
REP_DURATION = 1.6
SAMPLE_COUNTS = (3, 4, 5, 8, 12)


def _rep(times, duration: float, knee_min: float, hip_min: float | None = None) -> np.ndarray:
    """선 자세(170°) → 최저점 → 선 자세를 코사인으로 잇는 스쿼트 한 번. 열은 [무릎 L, 무릎 R, 고관절 L, 고관절 R].

    hip_min 을 안 주면 기준과 같은 무릎:고관절 결합(같은 동작을 더/덜 앉은 것)으로 정한다. 구간 밖 시각은 선 자세.
    """
    t = np.asarray(times, dtype=np.float64)
    s = np.where((t >= 0) & (t <= duration), 0.5 * (1 - np.cos(2 * np.pi * t / duration)), 0.0)
    if hip_min is None:
        hip_min = STAND - (STAND - knee_min) * (STAND - REF_HIP) / (STAND - REF_KNEE)
    knee = STAND - (STAND - knee_min) * s
    hip = STAND - (STAND - hip_min) * s
    return np.stack([knee, knee + 2, hip, hip - 2], axis=1)


# 기준은 영상에서 10fps 로 뽑는다(VIDEO_PROCESS_FPS).
REF = _rep(np.arange(0, REF_DURATION + 1e-9, 0.1), REF_DURATION, REF_KNEE, REF_HIP).tolist()


def _sample_times(duration: float, k: int) -> np.ndarray:
    """반복을 k 프레임으로 등간격으로 찍은 시각 — 최저점(가운데) 프레임이 늘 들어가게 맞춘다.

    k 가 짝수면 격자를 반 칸 밀어서, 실시간 버퍼처럼 «내려가는 도중부터 · 선 뒤까지» 찍힌 모양이 된다.
    """
    dt = duration / (k - 1)
    return duration / 2 + (np.arange(k) - (k - 1) // 2) * dt


def _score(k: int, knee_min: float, hip_min: float | None = None) -> float:
    ts = _sample_times(REP_DURATION, k)
    return compute_sync_rate(REF, _rep(ts, REP_DURATION, knee_min, hip_min).tolist(), user_times=ts.tolist())


# ─── a) 찍힌 프레임 수 ──────────────────────────────────────────────────────


@pytest.mark.parametrize("knee_min, hip_min", [
    (REF_KNEE, None),     # 기준 깊이
    (78.0, 66.0),         # 조금 얕고 결합도 다름
    (85.0, 75.0),         # 평행 근처
    (60.0, 55.0),         # 기준보다 깊음
])
def test_같은_반복을_몇_프레임으로_찍어도_점수가_3점_안에서_같다(knee_min, hip_min):
    scores = [_score(k, knee_min, hip_min) for k in SAMPLE_COUNTS]
    # 예전 식(찍힌 프레임 위 평균)은 이 합성 반복에서 폭이 10~12점이었다 — 3프레임만 유독 낮았다.
    assert max(scores) - min(scores) <= 3.0, dict(zip(SAMPLE_COUNTS, scores))


# ─── b) 시범보다 깊으면 감점하지 않는다 ─────────────────────────────────────


@pytest.mark.parametrize("k", SAMPLE_COUNTS)
def test_최저점_프레임만_더_깊으면_기준_깊이와_점수가_같거나_높다(k):
    """같은 반복에서 최저점 프레임만 무릎 50° 까지 더 내려간 경우 — 기준 최솟값에서 자르므로 정확히 감점 0."""
    ts = _sample_times(REP_DURATION, k)
    same = _rep(ts, REP_DURATION, REF_KNEE)
    deep = same.copy()
    bottom = int(np.argmin(same[:, 0]))
    deep[bottom] = _rep([REP_DURATION / 2], REP_DURATION, 50.0)[0]
    assert compute_sync_rate(REF, deep.tolist(), user_times=ts.tolist()) >= \
        compute_sync_rate(REF, same.tolist(), user_times=ts.tolist())


@pytest.mark.parametrize("k", SAMPLE_COUNTS)
def test_무릎_50도까지_앉은_반복이_기준_깊이_반복보다_낮지_않다(k):
    """매끈하게 무릎 50° 까지 앉은 반복 vs 같은 동작으로 기준 깊이(71°)까지 앉은 반복.

    중간 프레임이 찍히는 각도가 서로 달라서, 10fps 기준 위의 가장 가까운 점까지의 거리(양자화)로 ±1점 남짓
    흔들린다 — 그 폭만 허용한다. 예전 식(궤적 양방향)은 같은 비교에서 1.3~5점 낮았다.
    """
    deep = _score(k, 50.0)
    same = _score(k, REF_KNEE)
    assert deep >= same - 1.5, (deep, same)


def test_궤적_항은_기준보다_깊은_부분을_차이로_치지_않는다():
    ts = _sample_times(REP_DURATION, 5)
    same = _rep(ts, REP_DURATION, REF_KNEE)
    deep = same.copy()
    deep[2] = [40.0, 42.0, 30.0, 28.0]      # 최저점 프레임만 훨씬 깊게
    assert compute_dtw_distance(REF, deep.tolist(), ts.tolist()) == pytest.approx(
        compute_dtw_distance(REF, same.tolist(), ts.tolist()))


# ─── c) 얕을수록 엄격히 낮다 ────────────────────────────────────────────────


@pytest.mark.parametrize("k", SAMPLE_COUNTS)
def test_최저점이_기준보다_얕을수록_점수가_엄격히_내려간다(k):
    scores = [_score(k, REF_KNEE + d) for d in (0.0, 10.0, 20.0, 30.0)]
    assert all(a > b for a, b in zip(scores, scores[1:])), scores
    assert scores[0] >= 90.0     # 기준 깊이면 «양호»(기본 80) 를 넉넉히 넘는다


# ─── d) densify ──────────────────────────────────────────────────────────


def test_densify_길이_0_1_은_그대로():
    assert densify([]).shape == (0,)
    assert densify(np.empty((0, 4))).shape == (0, 4)
    one = densify([[1.0, 2.0, 3.0, 4.0]], times=[5.0])
    assert one.tolist() == [[1.0, 2.0, 3.0, 4.0]]


def test_densify_는_시각을_따라_선형_보간하고_마지막_시각을_정확히_포함한다():
    seq = [[0.0, 100.0], [12.0, 88.0]]
    out = densify(seq, times=[0.0, 1.0])
    assert out.shape == (13, 2)                         # 1초 / (1/12초) + 1
    assert np.allclose(out[:, 0], np.arange(13))
    assert np.allclose(out[:, 1], 100 - np.arange(13))

    uneven = [[10.0], [20.0], [40.0]]
    out = densify(uneven, times=[0.0, 0.3, 0.7])
    assert len(out) == 10                               # 0, 1/12, …, 8/12 + 마지막 0.7
    assert out[0, 0] == 10.0 and out[-1, 0] == 40.0


def test_densify_시각이_없으면_3fps_등간격으로_본다():
    seq = [[170.0], [90.0], [170.0]]
    out = densify(seq)
    assert len(out) == 9                                # 2 × (1/3초 ÷ 1/12초) + 1
    assert out[4, 0] == pytest.approx(90.0)
    assert DEFAULT_USER_FRAME_SEC == pytest.approx(1 / 3)


@pytest.mark.parametrize("bad_times", [
    [0.0, 0.5, 0.5],             # 같은 시각
    [0.0, 0.6, 0.3],             # 거꾸로
    [0.0, 0.5],                  # 길이가 다름
    [0.0, float("nan"), 1.0],    # 유한하지 않음
])
def test_densify_시각이_순증가가_아니면_등간격으로_대신한다(bad_times):
    seq = [[170.0, 160.0], [100.0, 90.0], [165.0, 150.0]]
    assert np.array_equal(densify(seq, times=bad_times), densify(seq, times=None))


def test_densify_는_점_수_상한을_넘지_않는다():
    out = densify([[0.0], [1.0]], times=[0.0, 10_000.0])
    assert len(out) <= DENSIFY_MAX_POINTS
    assert out[0, 0] == 0.0 and out[-1, 0] == 1.0


# ─── squat_analyzer 가 rep 의 프레임 시각을 넘긴다 ─────────────────────────────


def _state_with_frames(times, **kwargs) -> SessionState:
    state = SessionState(session_id=1, exercise_id=1, reference_angles=REF, **kwargs)
    for t in times:
        state.current_rep_frames.append(PerRepFrame(timestamp_sec=t, joint_coordinates="[]", angles=[170.0] * 4))
    return state


def test_마지막_선_프레임의_시각은_pose_py_가_찍을_값과_같다():
    # pose.py 는 process_frame(timestamp_sec=received_at) 직후 elapsed_sec(state, received_at) 으로 시각을 찍는다.
    state = _state_with_frames([10.0, 10.333, 10.667], elapsed_offset_sec=5.0)
    state.first_frame_mono = 1000.0
    assert _rep_times(state, terminal_mono=1006.0004) == [10.0, 10.333, 10.667, 11.0]


def test_기준점이_없거나_시계가_어긋나면_간격_중앙값으로_외삽한다():
    no_origin = _state_with_frames([1.0, 1.4, 1.7])
    assert _rep_times(no_origin, terminal_mono=123.0) == pytest.approx([1.0, 1.4, 1.7, 2.05])

    behind = _state_with_frames([1.0, 1.4, 1.7])
    behind.first_frame_mono = 1000.0
    assert _rep_times(behind, terminal_mono=1000.5) == pytest.approx([1.0, 1.4, 1.7, 2.05])


def test_버퍼_시각이_순증가가_아니면_None():
    assert _rep_times(_state_with_frames([1.0, 1.0, 1.3]), terminal_mono=None) is None
    assert _rep_times(_state_with_frames([]), terminal_mono=None) is None


def test_summarize_rep_은_시각을_compute_sync_rate_에_넘긴다(monkeypatch):
    seen = {}

    def fake(ref, user, user_times=None):
        seen["user"], seen["times"] = user, user_times
        return 88.0

    monkeypatch.setattr(squat_analyzer, "compute_sync_rate", fake)
    state = _state_with_frames([2.0, 2.33])
    state.first_frame_mono = 100.0
    event = StreamingSquatAnalyzer("squat")._summarize_rep(state, [171.0] * 4, terminal_mono=102.66)
    assert event.sync_rate == 88.0
    assert len(seen["user"]) == 3
    assert seen["times"] == [2.0, 2.33, 2.66]
    assert all(math.isfinite(t) for t in seen["times"])
