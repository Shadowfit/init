"""관리자 기준 영상 추출 — «기준 영상 모드» 와 실패 사유 통보 (2026-10-21).

무엇을 고정하나:
    1. `SquatCounter(reject_split_stance=False)` 는 «런지 자세» 로 보인 반복도 센다. 기본값(True)은
       지금처럼 버린다 — 사용자 경로(실시간·/video)는 기본값만 쓴다.
    2. `_extract_reference_poses_from_video` 의 실패는 관리자에게 보여줄 한국어 사유를 담은
       `ReferenceExtractionError` 로 나온다 — 열 수 없음 · 사람 없음 · 반복 없음.
    3. `_extract_and_send_reference` 는 실패를 로그로만 남기지 않고 `send_reference_failure` 로
       Spring 에 알린다. 예상 못 한 예외는 예외 이름만 싣는다.
    4. `send_reference_failure` 는 같은 RPC 에 error_message 를 싣고 좌표는 비운다(데드라인 포함).
    5. (영상이 있을 때만) 45° 3D 마네킹 스쿼트 영상에서 반복을 1회 이상 찾는다 — 예전엔 0회였다.

영상 디코드·검출기는 1~4 에서 가짜로 바꾼다 — CI 에는 영상도 MediaPipe 모델 실행 여유도 없다.
"""

from __future__ import annotations

import os
from types import SimpleNamespace
from unittest import mock

import cv2
import numpy as np
import orjson
import pytest

import exercise_pb2
from app.config import settings
from app.core import video_processor
from app.core.squat_analyzer import analyze_squat_frames
from app.core.squat_counter import SquatCounter
from app.grpc import exercise_servicer as servicer_mod
from app.grpc import spring_client
from app.grpc.exercise_servicer import (
    REFERENCE_MSG_CANNOT_OPEN,
    REFERENCE_MSG_NO_PERSON,
    REFERENCE_MSG_NO_REP,
    ReferenceExtractionError,
    _extract_and_send_reference,
    _extract_reference_poses_from_video,
)
from tests.test_exercise_separation import _LUNGE_BOTTOM, _LUNGE_STAND, _cycle
from tests.test_squat_analyzer import _frame


# ─── 1. 카운터 옵션 ──────────────────────────────────────────────────────────


def test_기본_카운터는_런지_자세_반복을_여전히_버린다():
    counter = SquatCounter()
    for t, landmarks in _cycle(_LUNGE_STAND, _LUNGE_BOTTOM):
        counter.update(landmarks, t)
    assert counter.rep_count == 0


def test_기준_영상_모드_카운터는_런지_자세_반복도_센다():
    counter = SquatCounter(reject_split_stance=False)
    for t, landmarks in _cycle(_LUNGE_STAND, _LUNGE_BOTTOM):
        counter.update(landmarks, t)
    assert counter.rep_count == 2


def test_analyze_squat_frames_가_옵션을_카운터까지_넘긴다():
    frames = _cycle(_LUNGE_STAND, _LUNGE_BOTTOM)
    landmarks = [lm for _, lm in frames]
    times = [t for t, _ in frames]
    _, live = analyze_squat_frames(landmarks, fps=10, timestamps_sec=times)
    _, ref = analyze_squat_frames(landmarks, fps=10, timestamps_sec=times, reject_split_stance=False)
    assert live.reps_detected == 0
    assert ref.reps_detected == 2


# ─── 2. 실패 사유 (가짜 영상·검출기) ───────────────────────────────────────────


class _FakeCapture:
    """cv2.VideoCapture 대역 — 정사각 검은 프레임 n 장(비율 1.0)."""

    def __init__(self, n_frames: int, fps: float = 10.0, opened: bool = True) -> None:
        self._left = n_frames
        self._fps = fps
        self._n = n_frames
        self._opened = opened

    def isOpened(self):
        return self._opened

    def get(self, prop):
        return {cv2.CAP_PROP_FPS: self._fps, cv2.CAP_PROP_FRAME_COUNT: float(self._n)}.get(prop, 0.0)

    def read(self):
        if self._left <= 0:
            return False, None
        self._left -= 1
        return True, np.zeros((64, 64, 3), dtype=np.uint8)

    def release(self):
        pass


def _fake_video(monkeypatch, capture: _FakeCapture, poses):
    """analyze_video 의 cv2·검출기를 가짜로. poses 는 프레임마다 검출기가 돌려줄 랜드마크(또는 None)."""
    fake_cv2 = SimpleNamespace(
        VideoCapture=lambda _path: capture,
        CAP_PROP_FPS=cv2.CAP_PROP_FPS,
        CAP_PROP_FRAME_COUNT=cv2.CAP_PROP_FRAME_COUNT,
        COLOR_BGR2RGB=cv2.COLOR_BGR2RGB,
        cvtColor=cv2.cvtColor,
    )
    monkeypatch.setattr(video_processor, "cv2", fake_cv2)
    it = iter(poses)
    monkeypatch.setattr(video_processor, "get_detector", lambda: SimpleNamespace(detect=lambda _rgb: next(it)))
    # 10fps 가짜 영상을 프레임 하나도 건너뛰지 않고 읽게 한다(VIDEO_PROCESS_FPS 기본값 10 과 같다).
    monkeypatch.setattr(settings, "VIDEO_PROCESS_FPS", 10)


def _squat_knees(fps: int = 10) -> list[float]:
    """서기 1초 → 내려가기 0.7초 → 바닥 0.5초 → 올라오기 0.7초 → 서기 1초 (무릎각)."""
    down = [175 - (175 - 85) * k / (0.7 * fps) for k in range(1, int(0.7 * fps) + 1)]
    return [175.0] * fps + down + [85.0] * (fps // 2) + down[::-1] + [175.0] * fps


def test_열리지_않는_파일은_열_수_없음으로_알린다(tmp_path):
    bad = tmp_path / "not-a-video.mp4"
    bad.write_bytes(b"this is not a video at all")
    with pytest.raises(ReferenceExtractionError) as e:
        _extract_reference_poses_from_video(str(bad))
    assert e.value.message == REFERENCE_MSG_CANNOT_OPEN


def test_열려도_한_장도_못_읽으면_열_수_없음으로_알린다(monkeypatch):
    _fake_video(monkeypatch, _FakeCapture(0), [])
    with pytest.raises(ReferenceExtractionError) as e:
        _extract_reference_poses_from_video("/fake.mp4")
    assert e.value.message == REFERENCE_MSG_CANNOT_OPEN


def test_사람이_안_보이는_영상은_사람_없음으로_알린다(monkeypatch):
    _fake_video(monkeypatch, _FakeCapture(30), [None] * 30)
    with pytest.raises(ReferenceExtractionError) as e:
        _extract_reference_poses_from_video("/fake.mp4")
    assert e.value.message == REFERENCE_MSG_NO_PERSON


def test_사람은_보이는데_반복이_없으면_반복_없음으로_알린다(monkeypatch):
    _fake_video(monkeypatch, _FakeCapture(30), [_frame(175) for _ in range(30)])
    with pytest.raises(ReferenceExtractionError) as e:
        _extract_reference_poses_from_video("/fake.mp4")
    assert e.value.message == REFERENCE_MSG_NO_REP


def test_반복이_있으면_비율_표식을_단_좌표를_돌려준다(monkeypatch):
    knees = _squat_knees()
    _fake_video(monkeypatch, _FakeCapture(len(knees)), [_frame(k) for k in knees])
    poses = _extract_reference_poses_from_video("/fake.mp4")
    assert poses
    assert poses[0].timestamp_sec == 0.0
    marker = [item for item in orjson.loads(poses[0].joint_coordinates) if item["index"] == -1]
    assert marker and marker[0]["image_aspect_ratio"] == 1.0


# ─── 3. 실패 통보 ────────────────────────────────────────────────────────────


def test_추출_실패는_사유를_Spring_에_보낸다(monkeypatch):
    def _fail(_path):
        raise ReferenceExtractionError(REFERENCE_MSG_NO_REP)

    monkeypatch.setattr(servicer_mod, "_extract_reference_poses_from_video", _fail)
    failure = mock.Mock(return_value=True)
    success = mock.Mock(return_value=True)
    monkeypatch.setattr(servicer_mod.spring_client, "send_reference_failure", failure)
    monkeypatch.setattr(servicer_mod.spring_client, "send_reference_poses", success)

    _extract_and_send_reference(7, "/videos/ref.mp4")

    failure.assert_called_once_with(7, REFERENCE_MSG_NO_REP)
    success.assert_not_called()


def test_예상_못_한_예외는_예외_이름을_실어_보낸다(monkeypatch):
    def _boom(_path):
        raise RuntimeError("mediapipe 내부 오류")

    monkeypatch.setattr(servicer_mod, "_extract_reference_poses_from_video", _boom)
    failure = mock.Mock(return_value=False)  # 통보 자체가 실패해도 예외가 새면 안 된다
    monkeypatch.setattr(servicer_mod.spring_client, "send_reference_failure", failure)
    monkeypatch.setattr(servicer_mod.spring_client, "send_reference_poses", mock.Mock())

    _extract_and_send_reference(7, "/videos/ref.mp4")

    failure.assert_called_once_with(7, "기준 좌표 추출 중 오류가 났어요: RuntimeError")


def test_성공하면_실패_통보는_안_보낸다(monkeypatch):
    poses = [exercise_pb2.PoseDataRequest(timestamp_sec=0.0, joint_coordinates="[]")]
    monkeypatch.setattr(servicer_mod, "_extract_reference_poses_from_video", lambda _p: poses)
    failure = mock.Mock()
    success = mock.Mock(return_value=True)
    monkeypatch.setattr(servicer_mod.spring_client, "send_reference_failure", failure)
    monkeypatch.setattr(servicer_mod.spring_client, "send_reference_poses", success)

    _extract_and_send_reference(7, "/videos/ref.mp4")

    success.assert_called_once_with(7, poses)
    failure.assert_not_called()


# ─── 4. send_reference_failure 의 요청 모양 ───────────────────────────────────


def test_send_reference_failure_는_같은_RPC_에_사유만_싣는다():
    seen = {}

    def record(request, **kwargs):
        seen["request"] = request
        seen["timeout"] = kwargs.get("timeout")
        return mock.MagicMock(success=True)

    stub = mock.MagicMock()
    stub.ExtractReferenceData.side_effect = record
    with mock.patch.object(spring_client, "get_stub", return_value=stub):
        assert spring_client.send_reference_failure(3, REFERENCE_MSG_NO_PERSON) is True

    request = seen["request"]
    assert request.exercise_id == 3
    assert request.error_message == REFERENCE_MSG_NO_PERSON
    assert len(request.extracted_poses) == 0
    assert seen["timeout"] == settings.BACKEND_GRPC_TIMEOUT_SECONDS


# ─── 5. 실영상 — 45° 3D 마네킹 스쿼트 (있을 때만) ─────────────────────────────

_MANNEQUIN_SQUAT = os.environ.get("SHADOWFIT_MANNEQUIN_SQUAT", "/vid/mannequin_squat.mp4")


@pytest.mark.skipif(not os.path.isfile(_MANNEQUIN_SQUAT), reason="마네킹 스쿼트 영상이 없다(CI)")
def test_기준_영상_모드는_45도_마네킹_스쿼트에서_반복을_찾는다():
    from app.core.reference_builder import _segment_reps

    result = video_processor.analyze_video(_MANNEQUIN_SQUAT, "squat", reference=True)
    assert result.squat_analysis.reps_detected >= 1
    assert len(_segment_reps(result.frames)) >= 1
