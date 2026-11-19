"""Repetition boundaries that cannot be inferred from the final count alone."""

from app.core.squat_analyzer import analyze_squat_frames
from app.core.squat_counter import SquatCounter, observe_squat
from app.core.reference_builder import _segment_reps
from app.models.pose import Landmark
from app.models.video import FrameResult
from tests.test_squat_analyzer import _frame


def _run(sequence, fps=3):
    counter = SquatCounter()
    for index, landmarks in enumerate(sequence):
        counter.update(landmarks, index / fps)
    return counter


def _hide_side(frame, side):
    for index in ((11, 23, 25, 27) if side == "left" else (12, 24, 26, 28)):
        frame[index].visibility = 0.1
    return frame


def _shift_side(frame, side, x):
    for index in ((11, 23, 25, 27) if side == "left" else (12, 24, 26, 28)):
        original = frame[index]
        frame[index] = Landmark(index=index, x=original.x + x,
                                y=original.y, z=original.z,
                                visibility=original.visibility)
    return frame


def test_entering_video_at_bottom_does_not_create_a_rep():
    counter = _run([_frame(85)] * 5 + [_frame(175)] * 4)
    assert counter.rep_count == 0
    assert counter.stage == "ready"


def test_partial_depth_does_not_create_a_rep():
    counter = SquatCounter()
    events = []
    for index, landmarks in enumerate([_frame(175)] * 3 + [_frame(120)] * 4 + [_frame(175)] * 4):
        counter.update(landmarks, index / 3)
        if counter.coaching_event:
            events.append(counter.coaching_event)
    assert counter.rep_count == 0
    assert events == ["이번 동작에서 충분히 내려간 장면을 확인하지 못했어요."]


def test_squat_depth_feedback_is_emitted_once_per_completed_rep():
    counter = SquatCounter()
    events = []
    for index, landmarks in enumerate([_frame(175)] * 3 + [_frame(85)] * 4 + [_frame(175)] * 5):
        counter.update(landmarks, index / 3)
        if counter.coaching_event:
            events.append(counter.coaching_event)
    assert counter.rep_count == 1
    assert events == ["스쿼트 깊이를 확인하고 1회 기록했어요."]


def test_lost_tracking_aborts_only_the_inflight_rep():
    rep = [_frame(175)] * 3 + [_frame(85)] * 4 + [_frame(175)] * 4
    counter = _run(rep + [_frame(85)] * 3 + [None] + [_frame(175)] * 4)
    assert counter.rep_count == 1


def test_one_frame_ankle_glitch_does_not_erase_squat_cycle():
    moved = _frame(85)
    for ankle in (27, 28):
        original = moved[ankle]
        moved[ankle] = Landmark(index=ankle, x=original.x + 0.3,
                                y=original.y, z=original.z, visibility=original.visibility)
    counter = _run([_frame(175)] * 3 + [_frame(85)] * 3 + [moved] + [_frame(175)] * 4)
    assert counter.rep_count == 1


def test_foot_repositioning_before_descent_updates_stance_anchor():
    sequence = ([_frame(175)] * 3 +
                [_shift_side(_frame(175), "right", 0.3) for _ in range(3)] +
                [_shift_side(_frame(85), "right", 0.3) for _ in range(4)] +
                [_shift_side(_frame(175), "right", 0.3) for _ in range(4)])
    assert _run(sequence).rep_count == 1


def test_persistent_foot_step_aborts_squat_cycle():
    moved = _frame(85)
    for ankle in (27, 28):
        original = moved[ankle]
        moved[ankle] = Landmark(index=ankle, x=original.x + 0.3,
                                y=original.y, z=original.z, visibility=original.visibility)
    counter = _run([_frame(175)] * 3 + [_frame(85)] * 3 + [moved] * 4 + [_frame(175)] * 4)
    assert counter.rep_count == 0


def test_one_visible_leg_can_complete_a_rep():
    sequence = ([_hide_side(_frame(175), "right") for _ in range(3)] +
                [_hide_side(_frame(85), "right") for _ in range(4)] +
                [_hide_side(_frame(175), "right") for _ in range(4)])
    counter = _run(sequence)
    assert counter.rep_count == 1
    assert observe_squat(sequence[0], 1.0, 0.55).visible_sides == (True, False)


def test_switching_the_only_visible_leg_aborts_inflight_rep():
    sequence = ([_hide_side(_frame(175), "right") for _ in range(3)] +
                [_hide_side(_frame(85), "right") for _ in range(3)] +
                [_hide_side(_frame(85), "left") for _ in range(3)] +
                [_hide_side(_frame(175), "left") for _ in range(4)])
    assert _run(sequence).rep_count == 0


def test_visible_leg_persistent_step_still_aborts_squat_cycle():
    moved = _hide_side(_frame(85), "right")
    original = moved[27]
    moved[27] = Landmark(index=27, x=original.x + 0.3,
                         y=original.y, z=original.z, visibility=original.visibility)
    sequence = ([_hide_side(_frame(175), "right") for _ in range(3)] +
                [_hide_side(_frame(85), "right") for _ in range(3)] +
                [moved] * 4 +
                [_hide_side(_frame(175), "right") for _ in range(4)])
    assert _run(sequence).rep_count == 0


def test_both_leg_chains_occluded_still_abort_inflight_rep():
    hidden = _frame(85)
    hidden[25].visibility = hidden[26].visibility = 0.1
    counter = _run([_frame(175)] * 3 + [_frame(85)] * 3 +
                   [hidden] + [_frame(175)] * 4)
    assert counter.rep_count == 0


def test_reference_segments_follow_completed_repetitions():
    sequence = [_frame(175)] * 3 + [_frame(85)] * 4 + [_frame(175)] * 4
    metrics, summary = analyze_squat_frames(sequence, fps=3)
    frames = [FrameResult(frame_index=i, timestamp=i / 3, landmarks=landmarks,
                          angles=[float(i)], squat_metrics=metrics[i])
              for i, landmarks in enumerate(sequence)]
    segments = _segment_reps(frames)
    assert summary.reps_detected == 1
    assert len(segments) == 1
    assert segments[0].rep_index == 1
    # At fps=3 the sample gap (1/3 s) already exceeds standing_confirm_sec (0.2s),
    # so the very first standing sample after the bottom (frame 7) is itself enough
    # dwell evidence and completes the rep -- a second standing sample is no longer
    # required. See squat-repetition-counting.md's time-based-sampling note.
    assert segments[0].end_frame_index == 7


# ── 스쿼트 깊이 모드 (FULL/HALF, 2026-09-21) ────────────────────────────────
# HALF_SQUAT_THRESHOLDS의 bottom_hip/min_hip_drop은 실촬영 검증 전이라(VALIDATE 주석
# 참고) 여기서는 "FSM이 두 모드를 다르게 취급하는가"만 고정한다 — 정확한 깊이 판정
# 기준 자체의 검증은 docs/verification/의 하프 스쿼트 실촬영 리포트가 담당한다.

from app.core.squat_counter import DEPTH_MODE_THRESHOLDS, HALF_SQUAT_THRESHOLDS  # noqa: E402


def test_half_squat_thresholds_are_internally_consistent():
    # __post_init__이 통과한다는 것 자체가 검증 — 생성이 실패하면 여기서 예외가 난다.
    assert HALF_SQUAT_THRESHOLDS.bottom_knee + HALF_SQUAT_THRESHOLDS.bottom_knee_margin == 140.0
    assert DEPTH_MODE_THRESHOLDS["FULL"].bottom_knee == 100.0
    assert DEPTH_MODE_THRESHOLDS["HALF"] is HALF_SQUAT_THRESHOLDS


def test_half_depth_completes_under_half_thresholds_but_not_full_thresholds():
    # 무릎 130도까지만 내려가는 시퀀스 — 하프 밴드(115~140) 안, 풀 스쿼트 기준(<=115)엔 못 미침.
    sequence = [_frame(175)] * 3 + [_frame(130)] * 4 + [_frame(175)] * 4

    half_counter = SquatCounter(HALF_SQUAT_THRESHOLDS)
    for index, landmarks in enumerate(sequence):
        half_counter.update(landmarks, index / 3)
    assert half_counter.rep_count == 1
    assert half_counter.stage == "ready"

    full_counter = SquatCounter(DEPTH_MODE_THRESHOLDS["FULL"])
    for index, landmarks in enumerate(sequence):
        full_counter.update(landmarks, index / 3)
    assert full_counter.rep_count == 0
    # 바닥 조건(무릎<=115)을 한 번도 못 만족해 "bottom" 단계에 들어가지 못한다 —
    # test_partial_depth_does_not_create_a_rep과 같은 경로(얕음 피드백)를 탄다.
    assert full_counter.stage == "ready"


# ── 미인정 시도 (2026-10-21) ─────────────────────────────────────────────────
# 횟수는 원래 규칙 그대로다 — 바닥은 연속 두 표본, 처음 선 자세는 고관절 ≥ 150°. 사람 판정과 맞는 규칙이다
# (docs/verification/2026-09-21-verification-report.md §4). 같은 날 «바닥 한 장으로 확정»·«처음 선 자세 고관절 ≥ 140°»
# 로 넓혔다가 사람 판정 «정상 0회» 인 squat_shallow_01 을 4회로 세서 되돌렸다. 대신 내려갔다 올라왔지만 인정되지
# 않은 «진짜 시도» 는 사라지지 않고 미인정(rejected_count·rejected_event)으로 남는다
# (docs/decisions/counted-reps-vs-scored-attempts.md §6).

import json  # noqa: E402
from pathlib import Path  # noqa: E402

import pytest  # noqa: E402

from tests.test_exercise_separation import _LUNGE_BOTTOM, _LUNGE_STAND, _cycle  # noqa: E402


def _run_timed(timed_frames, counter=None):
    """(시각, 랜드마크) 열을 흘려보내고, 미인정이 난 프레임의 사유를 순서대로 모은다."""
    counter = counter or SquatCounter()
    reasons = []
    for when, landmarks in timed_frames:
        counter.update(landmarks, when)
        if counter.rejected_event:
            reasons.append(counter.rejected_event)
    return counter, reasons


def _run_rejections(sequence, fps=3, counter=None):
    return _run_timed([(index / fps, landmarks) for index, landmarks in enumerate(sequence)], counter)


def test_single_bottom_sample_at_3fps_is_rejected_not_counted():
    # 바닥 조건(무릎 101°)이 한 장에만 찍힌 3fps 시도 — 바닥은 두 표본이 있어야 확정하므로 세지 않는다.
    # 내려가는 표본(130°)부터 다시 설 때까지 0.67초, 골반도 충분히 내려간 «진짜 시도» 라 미인정으로 남는다.
    counter, reasons = _run_rejections([_frame(175)] * 3 + [_frame(130), _frame(101)] + [_frame(175)] * 4)
    assert counter.rep_count == 0
    assert counter.rejected_count == 1
    assert reasons == ["DEPTH_NOT_CONFIRMED"]


def test_single_bottom_sample_alone_is_too_brief_to_be_an_attempt():
    # 되돌린 «한 장으로 확정» 을 고정하던 바로 그 시퀀스. 선 자세가 아닌 표본이 바닥 한 장(0.33초)뿐이라
    # 기존 «진짜 시도» 문턱(min_active_sec 0.4초)에 못 미친다 — 횟수도, 미인정도, 깊이 안내도 없다.
    counter = SquatCounter()
    coaching = []
    for index, landmarks in enumerate([_frame(175)] * 3 + [_frame(101)] + [_frame(175)] * 4):
        counter.update(landmarks, index / 3)
        if counter.coaching_event:
            coaching.append(counter.coaching_event)
    assert counter.rep_count == 0
    assert counter.rejected_count == 0
    assert coaching == []


def test_single_bottom_spike_on_a_fast_feed_still_does_not_count():
    # 촘촘한 입력(30fps)에서 한 장짜리 바닥은 체류가 아니라 잡음일 수 있다 — 예전 규칙 그대로
    counter = _run([_frame(175)] * 10 + [_frame(95)] + [_frame(175)] * 10, fps=30)
    assert counter.rep_count == 0


def test_standing_with_hip_flexion_below_150_never_calibrates():
    # 되돌린 것(2026-10-21): 무릎은 다 폈어도 고관절이 150°(160 − initial_hip_margin 10) 밑이면 «처음 선 자세» 로
    # 보지 않는다. 그래서 그 뒤의 굽힘은 횟수도 미인정도 아니다 — 준비되기 전의 동작은 «시도» 가 아니다.
    sequence = [_frame(175, 30)] * 3 + [_frame(85, 30)] * 4 + [_frame(175, 30)] * 4
    counter = _run(sequence)
    assert observe_squat(sequence[0], 1.0, 0.55).hip_angle < 150
    assert counter.stage == "waiting_for_standing"
    assert counter.rep_count == 0
    assert counter.rejected_count == 0


def test_partial_depth_attempt_is_rejected_once_and_event_lasts_one_update():
    counter, reasons = _run_rejections([_frame(175)] * 3 + [_frame(120)] * 4 + [_frame(175)] * 4)
    assert counter.rep_count == 0
    assert counter.rejected_count == 1
    # 사유는 미인정이 난 그 update 에만 실린다 — 다음 update 에서 지워지지 않으면 여기에 여러 번 쌓인다.
    assert reasons == ["DEPTH_NOT_CONFIRMED"]
    assert counter.rejected_event is None


def test_small_knee_wobble_is_not_an_attempt():
    # 무릎이 선 자세 밴드를 살짝 벗어났지만(150°) 골반이 거의 안 내려갔다 — 기존에도 안내 없이 넘기던 동작이다.
    counter, reasons = _run_rejections([_frame(175)] * 3 + [_frame(150)] * 3 + [_frame(175)] * 4)
    assert counter.rep_count == 0
    assert counter.rejected_count == 0 and reasons == []


def test_split_stance_attempts_are_rejected_with_their_reason():
    counter, reasons = _run_timed(_cycle(_LUNGE_STAND, _LUNGE_BOTTOM))
    assert counter.rep_count == 0
    assert reasons == ["SPLIT_STANCE", "SPLIT_STANCE"]
    assert counter.rejected_count == 2


def test_reference_extraction_mode_counts_split_stance_instead_of_rejecting():
    # 관리자 기준 영상 추출(reject_split_stance=False)은 런지 자세로 보인 반복도 센다 — 미인정이 아니다.
    counter, reasons = _run_timed(_cycle(_LUNGE_STAND, _LUNGE_BOTTOM), SquatCounter(reject_split_stance=False))
    assert counter.rep_count == 2
    assert counter.rejected_count == 0 and reasons == []


def _moved_feet(knee):
    frame = _frame(knee)
    for ankle in (27, 28):
        original = frame[ankle]
        frame[ankle] = Landmark(index=ankle, x=original.x + 0.3, y=original.y, z=original.z,
                                visibility=original.visibility)
    return frame


@pytest.mark.parametrize("timed_frames", [
    # 추적 끊김 — 바닥에서 사람을 놓쳤다
    pytest.param([(i / 3, f) for i, f in enumerate(
        [_frame(175)] * 3 + [_frame(85)] * 3 + [None] + [_frame(175)] * 4)], id="tracking_lost"),
    # 발 이동 — 바닥에서 발이 유예(0.7초)보다 오래 벗어났다
    pytest.param([(i / 3, f) for i, f in enumerate(
        [_frame(175)] * 3 + [_frame(85)] * 3 + [_moved_feet(85)] * 4 + [_frame(175)] * 4)], id="foot_moved"),
    # 다리 바뀜 — 보이는 다리가 동작 중에 바뀌었다
    pytest.param([(i / 3, f) for i, f in enumerate(
        [_hide_side(_frame(175), "right") for _ in range(3)] +
        [_hide_side(_frame(85), "right") for _ in range(3)] +
        [_hide_side(_frame(85), "left") for _ in range(3)] +
        [_hide_side(_frame(175), "left") for _ in range(4)])], id="leg_switched"),
    # 오래 앉음 — 바닥 10초(> max_bottom_sec 8초)
    pytest.param([(i / 3, f) for i, f in enumerate(
        [_frame(175)] * 3 + [_frame(85)] * 30 + [_frame(175)] * 4)], id="sat_too_long"),
    # 영상 공백 — 바닥 뒤 2.3초(> max_gap_sec 1.2초) 동안 표본이 없었다
    pytest.param([(i / 3, _frame(175)) for i in range(3)] + [(1 + i / 3, _frame(85)) for i in range(3)]
                 + [(4 + i / 3, _frame(175)) for i in range(4)], id="gap"),
])
def test_invalidated_attempts_are_not_rejected_attempts(timed_frames):
    # 판정 불가(invalidate)로 끝난 동작은 «시도» 가 아니다 — 미인정으로 세면 사용자 탓이 아닌 일을 탓하게 된다.
    # (rep_count == 0 이 곧 «무효화가 일어났다» 는 증거다 — 안 일어났으면 다섯 경우 모두 1회로 셌을 동작이다.)
    counter, reasons = _run_timed(timed_frames)
    assert counter.rep_count == 0
    assert counter.rejected_count == 0 and reasons == []


def test_rejected_count_survives_invalidate_and_starts_from_the_seed():
    # rep_count 와 같은 규약 — 재부착 때 Spring 이 준 값에서 시작하고, 판정 불가(invalidate)로 지워지지 않는다.
    counter, _ = _run_rejections([_frame(175)] * 3 + [_frame(120)] * 4 + [_frame(175)] * 4,
                                 counter=SquatCounter(rejected_count=4))
    assert counter.rejected_count == 5
    counter.invalidate()
    assert counter.rejected_count == 5
    assert counter.rejected_event is None


_FIXTURES = Path(__file__).resolve().parents[2] / "frontend" / "lib" / "motion" / "fixtures"


@pytest.mark.parametrize("name, reps, rejected", [
    ("squat_normal_01", 5, 0),
    ("squat_normal_02", 5, 0),
    ("squat_side_01", 5, 0),
    # 사람 판정: 얕은 시도 5회·정상 0회(docs/verification/2026-09-21-verification-report.md §4). 앞의 3회는 선 자세
    # 인식(4.02초) 전에 일어나 «시도» 로도 안 잡히고, 뒤의 2회(바닥 조건 경계에 한 표본만 닿음)가 깊이 미확인
    # 미인정이다 — 실측 5.12초·6.58초에 DEPTH_NOT_CONFIRMED.
    ("squat_shallow_01", 0, 2),
])
def test_real_recordings_match_human_labels(name, reps, rejected):
    path = _FIXTURES / f"{name}.json"
    if not path.exists():
        pytest.skip("실촬영 좌표 기록(frontend/lib/motion/fixtures)이 없다")
    rec = json.loads(path.read_text(encoding="utf-8"))
    counter = SquatCounter()
    reasons = []
    for frame in rec["frames"]:
        if frame["lm"]:
            landmarks = [Landmark(index=i, x=p[0], y=p[1], z=p[2], visibility=p[3])
                         for i, p in enumerate(frame["lm"])]
            counter.update(landmarks, frame["t"], rec["aspect"])
            if counter.rejected_event:
                reasons.append(counter.rejected_event)
    assert counter.rep_count == reps
    assert counter.rejected_count == rejected
    assert reasons == ["DEPTH_NOT_CONFIRMED"] * rejected
