"""스쿼트 실시간/영상 분석. 반복 판정은 squat_counter 한 곳에서 수행한다."""
from __future__ import annotations

import math
import statistics
import time
from dataclasses import dataclass, replace
from typing import TYPE_CHECKING

from app.core.angle_calculator import extract_angles
from app.core.dtw_calculator import compute_sync_rate, resolve_sync_thresholds
from app.core.squat_counter import DEPTH_MODE_THRESHOLDS, SquatCounter, SquatThresholds
from app.models.pose import Landmark
from app.models.video import SquatAnalysisResult, SquatFrameMetrics
from app.utils.constants import LANDMARK

if TYPE_CHECKING:  # 타입 표기 전용 — 런타임에 session_state 를 부르면 그 모듈의 gRPC 의존까지 끌려온다
    from app.grpc.session_state import PerRepFrame


def _mean_landmark(landmarks_by_index: dict[int, Landmark], left_name: str, right_name: str):
    left, right = (landmarks_by_index[LANDMARK[name]] for name in (left_name, right_name))
    return ((left.x + right.x) / 2, (left.y + right.y) / 2)


def _torso_tilt_degrees(landmarks_by_index: dict[int, Landmark]) -> float:
    shoulder_x, shoulder_y = _mean_landmark(
        landmarks_by_index, "LEFT_SHOULDER", "RIGHT_SHOULDER"
    )
    hip_x, hip_y = _mean_landmark(landmarks_by_index, "LEFT_HIP", "RIGHT_HIP")
    return abs(math.degrees(math.atan2(shoulder_x - hip_x, hip_y - shoulder_y + 1e-8)))


def analyze_squat_frames(
    landmark_frames: list[list[Landmark] | None], *,
    bottom_threshold: float = 100.0,
    standing_threshold: float = 160.0,
    fps: float = 3.0,
    timestamps_sec: list[float] | None = None,
    image_aspect_ratio: float = 1.0,
    reject_split_stance: bool = True,
) -> tuple[list[SquatFrameMetrics | None], SquatAnalysisResult]:
    """영상에서도 실시간과 같은 단계 기계를 사용한다.

    fps를 알면 전달해야 한다. 횟수 하한은 초 단위다.
    reject_split_stance: False 면 «런지 자세» 로 보인 반복도 센다 — 관리자 기준 영상 추출 전용
        (SquatCounter.reject_split_stance 참고). 사용자 영상 경로는 기본값(True)을 그대로 쓴다.
    """
    if not math.isfinite(fps) or fps <= 0:
        raise ValueError("fps must be positive")
    if timestamps_sec is not None and len(timestamps_sec) != len(landmark_frames):
        raise ValueError("timestamps must match frames")
    thresholds = replace(SquatThresholds(), bottom_knee=bottom_threshold,
                         standing_knee=standing_threshold)
    counter = SquatCounter(thresholds, reject_split_stance=reject_split_stance)
    result_frames: list[SquatFrameMetrics | None] = []
    valid_frames = 0
    deepest = 180.0
    tilt_samples = []
    for i, landmarks in enumerate(landmark_frames):
        when = timestamps_sec[i] if timestamps_sec is not None else i / fps
        obs, _ = counter.update(landmarks, when, image_aspect_ratio)
        if obs is None:
            result_frames.append(None)
            continue
        valid_frames += 1
        deepest = min(deepest, obs.knee_angle)
        tilt_samples.append(obs.torso_tilt)
        result_frames.append(SquatFrameMetrics(
            knee_angle=round(obs.knee_angle, 2), hip_angle=round(obs.hip_angle, 2),
            torso_tilt=round(obs.torso_tilt, 2), hip_height=round(obs.hip_height, 4),
            phase=counter.phase, cycle_stage=counter.stage, rep_count=counter.rep_count,
        ))
    ratio = round(valid_frames / len(landmark_frames), 2) if landmark_frames else 0.
    feedback = []
    if valid_frames == 0:
        feedback.append("관절이 보이는 프레임을 확보하지 못했습니다.")
    elif counter.rep_count == 0:
        feedback.append("서기, 굽히기, 다시 서기로 이어지는 완전한 스쿼트를 감지하지 못했습니다.")
    if valid_frames and ratio < 0.7:
        feedback.append("전신이 보이도록 촬영 위치를 조정해주세요.")
    return result_frames, SquatAnalysisResult(
        reps_detected=counter.rep_count, current_phase=counter.phase,
        deepest_knee_angle=round(deepest, 2) if valid_frames else 0.,
        mean_torso_tilt=round(sum(tilt_samples) / len(tilt_samples), 2) if tilt_samples else 0.,
        quality_score=None, feedback=feedback, valid_frame_ratio=ratio,
    )


def _rep_times(state, terminal_mono: float | None, frames=None) -> list[float] | None:
    """싱크로율 궤적 항(시간축 보간, dtw_calculator.densify)에 넘길 이번 rep 의 프레임 시각(초).

    frames: 채점할 버퍼 프레임. None 이면 state.current_rep_frames(진행 중 rep). 미인정 시도는 process_frame 이
    버퍼를 비우기 전에 떠 둔 스냅샷을 넘긴다.

    버퍼 프레임의 시각은 pose.py 가 `elapsed_sec(state, received_at)` 로 찍은 «세션 경과 초» 다. 마지막(선)
    프레임은 아직 버퍼에 없다 — process_frame 이 돌아간 뒤 pose.py 가 넣는다. 그런데 pose.py 는 process_frame 에
    timestamp_sec=received_at 을 넘기고 곧바로 **같은 received_at** 으로 elapsed_sec 을 부르므로, 같은 식
    (elapsed_offset_sec + (now − first_frame_mono), 소수 셋째 자리)을 여기서 부작용 없이 계산하면 그 프레임이
    받게 될 시각과 정확히 같다.
    기준점이 없거나(first_frame_mono 가 아직 없음) 그 값이 버퍼 마지막 시각보다 앞서면(시계가 다른 호출자 —
    테스트·스크립트) 버퍼 프레임 간격의 중앙값만큼 뒤로 외삽한다. 그래도 유한한 순증가가 아니면 None
    (→ densify 가 ~3fps 등간격으로 본다).
    """
    try:
        times = [float(f.timestamp_sec)
                 for f in (state.current_rep_frames if frames is None else frames)]
    except (AttributeError, TypeError, ValueError):
        return None
    terminal = None
    first = getattr(state, "first_frame_mono", None)
    if terminal_mono is not None and first is not None:
        terminal = round(getattr(state, "elapsed_offset_sec", 0.0) + (terminal_mono - first), 3)
    if terminal is None or (times and not terminal > times[-1]):
        if len(times) < 2:
            return None
        terminal = times[-1] + statistics.median(b - a for a, b in zip(times, times[1:]))
    out = times + [terminal]
    if all(math.isfinite(t) for t in out) and all(b > a for a, b in zip(out, out[1:])):
        return out
    return None


def _sync_rate(state, frames, terminal_angles: list[float], terminal_mono: float | None) -> float:
    """버퍼 프레임 + 끝(선) 프레임을 기준 궤적과 비교한 싱크로율 — 인정 반복과 미인정 시도가 같은 식을 쓴다."""
    user_angles = [f.angles for f in frames] + [terminal_angles]
    if not state.reference_angles or not user_angles:
        return 0.
    try:
        return compute_sync_rate(state.reference_angles, user_angles,
                                 user_times=_rep_times(state, terminal_mono, frames))
    except (ValueError, IndexError, TypeError):
        return 0.


@dataclass
class StreamingRepEvent:
    rep_number: int
    sync_rate: float
    feedback_message: str


@dataclass
class RejectedAttemptEvent:
    """«미인정 시도» 1건의 요약 (2026-10-21) — Spring `SaveRejectedAttempt` 로 보낼 재료.

    process_frame 이 `state.pending_rejected_attempt` 에 올려 두고 pose.py 가 같은 락 안에서 꺼내 락 밖에서 보낸다
    (process_frame 의 5-튜플 반환형을 바꾸지 않으려고 상태로 넘긴다).
    """

    # 이 세션의 미인정 번호(1부터) = 그 시점의 state.rejected_count. Spring 의 멱등키다.
    attempt_number: int
    reason: str  # "DEPTH_NOT_CONFIRMED" | "SPLIT_STANCE" (SquatCounter.rejected_event)
    # 인정 반복과 같은 식(_sync_rate: 시도 프레임 + 끝 프레임, 실제 프레임 시각)으로 매긴 0~100
    sync_rate: float
    # 시도 중 가장 깊이 앉은 순간 — 시도 프레임(버퍼) 중 smoothed_knee_angle 이 가장 작은 것.
    # 리포트에 보낼 무릎각·체간·시각·좌표가 전부 이 프레임에서 나온다. 버퍼가 비어 있었으면(시도 도중 발목 튐 등으로
    # 비워진 경우) None — 호출부(pose.py)가 끝(선) 프레임으로 대신한다.
    deepest_frame: PerRepFrame | None = None


class StreamingSquatAnalyzer:
    """세션별 SquatCounter를 이용해 한 프레임씩 집계한다.

    기준영상과의 DTW 유사도는 기존 호환 지표이며, 논문의 임상 자세 점수가 아니다.

    🔴 이 분석기 자체는 app/core/analyzer_registry.py가 모듈 로드 시점에 딱 하나 만들어
    모든 세션이 공유하는 stateless 싱글턴이다. 그래서 `self.thresholds`를 세션마다 바꿀 수
    없다 — 대신 스쿼트 깊이 모드(FULL/HALF, 2026-09-21)는 세션별 상태인 `state.depth_mode`에
    저장되고, SquatCounter를 만드는 이 시점에만 그 값을 읽어 임계값을 고른다.
    """

    def __init__(self, exercise_type: str = "squat",
                 thresholds: SquatThresholds | None = None) -> None:
        self.exercise_type = exercise_type
        self.thresholds = thresholds or SquatThresholds()

    def process_frame(self, state, landmarks: list[Landmark], *,
                      timestamp_sec: float | None = None,
                      image_aspect_ratio: float = 1.0
                      ) -> tuple[list[float] | None, float | None, StreamingRepEvent | None,
                                 float | None, float | None]:
        now = time.monotonic() if timestamp_sec is None else timestamp_sec
        # 이번 프레임에서 난 미인정만 담는다 — 호출부가 안 꺼내 간 이전 값이 다음 프레임 것으로 섞이지 않게 비운다.
        state.pending_rejected_attempt = None
        if state.squat_counter is None:
            # 세션의 depth_mode로 임계값을 고른다. 인식 못 하는 값(또는 이 필드가 없는 옛
            # 상태 객체)은 이 분석기 자신의 기본값(self.thresholds, 곧 FULL)으로 폴백한다.
            thresholds = DEPTH_MODE_THRESHOLDS.get(
                getattr(state, "depth_mode", "FULL"), self.thresholds
            )
            # 미인정 수도 rep 수처럼 재부착 때 Spring 이 준 값(initial_rejected_count)에서 잇는다.
            state.squat_counter = SquatCounter(thresholds, rep_count=state.rep_count,
                                               rejected_count=getattr(state, "rejected_count", 0))
        counter = state.squat_counter
        before = counter.stage
        obs, completed = counter.update(landmarks, now, image_aspect_ratio)
        state.rep_state = counter.stage
        state.frame_index += 1
        if obs is None:
            # 미인정은 늘 관측값과 함께 난다(squat_counter 가 raw 를 돌려주는 분기) — 여기서 버려지는 시도는 없다.
            state.current_rep_frames.clear()
            return None, None, None, None, None
        # 미인정 시도의 프레임 — 바로 아래에서 버퍼를 비우기 **전에** 떠 둔다(그 시도의 점수·최저점 재료다).
        rejected_frames = list(state.current_rep_frames) if counter.rejected_event else None
        if before != "waiting_for_standing" and counter.stage == "waiting_for_standing":
            state.current_rep_frames.clear()
        # 시도 시작(준비 → 내려가는 중)과 세지 않은 시도의 끝(내려가는 중 → 준비: 깊이 미확인·얕은 동작,
        # 바닥 → 준비: 런지 자세)에서 버퍼를 비운다 — 바닥에서 버린 런지 자세 시도가 다음 반복에 섞이지 않게
        # 하려고 «바닥» 도 같이 본다(2026-10-21). 시작 쪽의 «준비 → 바닥» 은 방어용이다: 지금 규칙에서는
        # 바닥 확정에 «내려가는 중» 에서 두 표본 이상이 필요해 준비에서 곧장 바닥이 될 수 없다.
        active = ("descending", "bottom")
        if (before == "ready" and counter.stage in active
                or before in active and counter.stage == "ready" and not completed):
            state.current_rep_frames.clear()
        angles = extract_angles(landmarks, self.exercise_type, image_aspect_ratio)
        knee = round(obs.knee_angle, 2)
        state.previous_smoothed_knee = knee
        # 체간 기울기 점수(TrunkLeanAnalysisCalculator)용 — obs.torso_tilt는 이미 매 프레임
        # 계산되고 있었지만(squat_counter.py) 지금까지는 버려졌다. BACK_BENT 실시간 판정
        # (pose.py)과는 별개 경로이니 여기서 새로 계산하지 않고 이미 있는 값만 흘려보낸다.
        torso_tilt = round(obs.torso_tilt, 2)
        if rejected_frames is not None:
            # 미인정 시도 (2026-10-21) — 횟수(rep_count)·rep 버퍼·Spring pose_data 와는 무관하게 따로 요약해 둔다.
            state.rejected_count = counter.rejected_count
            state.pending_rejected_attempt = self._summarize_rejected(
                state, counter.rejected_event, rejected_frames, angles, terminal_mono=now
            )
        # 스쿼트는 "보폭" 개념이 없다(양발이 나란함) — 5번째 자리는 런지 전용
        # LungeStepLengthAnalysisCalculator 몫이라 항상 None을 흘려보낸다.
        if not completed:
            return angles, knee, None, torso_tilt, None
        state.rep_count = counter.rep_count
        return angles, knee, self._summarize_rep(state, angles, terminal_mono=now), torso_tilt, None

    def _summarize_rejected(self, state, reason: str, frames: list, terminal_angles: list[float], *,
                            terminal_mono: float | None = None) -> RejectedAttemptEvent:
        """미인정 시도 하나를 인정 반복과 같은 식으로 채점하고, 가장 깊이 앉은 순간을 고른다.

        frames 는 시도 시작(준비 → 내려가는 중)부터 끝 직전까지의 버퍼 스냅샷이고, 끝(다시 선) 프레임은
        아직 버퍼에 없다 — _summarize_rep 과 똑같이 terminal_angles 로 덧붙여 채점한다. 최저점은 버퍼에서만
        고른다: 끝 프레임은 선 자세라 최저점일 수 없고, 버퍼가 비었을 때만 호출부가 끝 프레임으로 대신한다.
        """
        deepest = min(frames, key=lambda f: f.smoothed_knee_angle) if frames else None
        return RejectedAttemptEvent(
            attempt_number=state.rejected_count,
            reason=reason,
            sync_rate=_sync_rate(state, frames, terminal_angles, terminal_mono),
            deepest_frame=deepest,
        )

    def _summarize_rep(self, state, terminal_angles: list[float], *,
                       terminal_mono: float | None = None) -> StreamingRepEvent:
        # 최종 선 프레임은 HTTP 경로에서 process_frame 반환 후 버퍼에 들어간다.
        score = _sync_rate(state, state.current_rep_frames, terminal_angles, terminal_mono)
        # 임계값은 운동별 한 쌍(2026-10-21) — 세션 생성 때 Spring 이 준 값이 state 에 있다.
        # 이 필드가 없는 옛 상태 객체는 기본값으로 폴백한다(depth_mode 와 같은 처리).
        # 🔴 세 문구는 바꾸지 말 것 — pose.py 의 BACK_BENT 게이트가 `!= "자세 양호"` 로 비교한다.
        good, warn = resolve_sync_thresholds(
            getattr(state, "sync_good_threshold", None),
            getattr(state, "sync_warn_threshold", None),
        )
        if score >= good:
            message = "자세 양호"
        elif score >= warn:
            message = "자세 보정 필요"
        else:
            message = "즉시 자세 수정 필요"
        return StreamingRepEvent(state.rep_count, score, message)
