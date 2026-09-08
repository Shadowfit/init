"""Spring→AI REST 미러(`/internal/analysis/*`) 단위 테스트.

`ExerciseServicer`를 in-process로 그대로 호출하는 얇은 어댑터라(docs/decisions/
grpc-webclient-empirical-comparison.md §8), 검증 대상은 서비서의 판정 로직 자체가 아니라
**"gRPC로 갔을 때와 같은 결과가 REST로도 나오는가"** — 특히 `context.abort()`를
`_FakeContext`로 흉내낸 뒤 HTTP 400으로 옮기는 지점이 이 파일에서 가장 위험한 새 코드다.

MediaPipe 검출기 풀은 건드리지 않는다 — 여기서 쓰는 경로(미지원 종목 거절, 없는 세션)는
전부 검출기 풀 획득 이전에 끝난다(`resolve_exercise_type`이 먼저 걸러낸다). 성공 경로(실제
분석기 획득까지 가는 것)는 이 저장소의 다른 gRPC 서비서 테스트에도 없다 — 대칭을 맞췄다.
"""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import internal_analysis
from app.grpc import exercise_servicer as servicer_mod


class _FakePool:
    """검출기 풀 대역(test_stop_intake_warning.py와 같은 이유) — 진짜 풀은 컨테이너 메모리
    한도나 POSE_DETECTOR_POOL_SIZE 없이는 크기를 못 정한다(근거 없는 기본값을 거부하는
    설계). 이 파일이 보는 건 REST 어댑터의 마샬링/거절 경로지 풀 크기 산정이 아니다."""

    def acquire(self, _session_id):
        return True

    def release(self, _session_id):
        return False

    def status(self):
        return (0, 1)


def _client() -> TestClient:
    app = FastAPI()
    app.include_router(internal_analysis.router, prefix="/api/v1")
    return TestClient(app)


def test_start_analysis_rejects_unsupported_exercise_as_400():
    """gRPC의 INVALID_ARGUMENT(#147)와 대칭 — HTTP 400, WebClientAiAnalysisClient가
    이를 ClientRejected로 분류한다(GrpcAiAnalysisClient.classify()의 REST 대응)."""
    client = _client()

    res = client.post(
        "/api/v1/internal/analysis/start",
        json={
            "exercise_id": 2,  # 런지 — 분석기 없음(analyzer_registry.py:39-43)
            "session_id": 999,
            "reference_source": "https://youtu.be/dummy",
            "reference_poses": [],
            "persona": "BEGINNER",
            "session_nonce": "",
        },
    )

    assert res.status_code == 400


def test_start_analysis_accepts_supported_exercise_request_shape(monkeypatch):
    """지원 종목(exercise_id=1=squat)이면 abort 분기를 안 타고 검출기 풀까지 간다 —
    풀 획득에 성공하면 성공 응답 스키마(session_id)를 낸다."""
    monkeypatch.setattr(servicer_mod, "get_pool", lambda: _FakePool())
    client = _client()

    res = client.post(
        "/api/v1/internal/analysis/start",
        json={
            "exercise_id": 1,
            "session_id": 12345,
            "reference_source": "https://youtu.be/dummy",
            "reference_poses": [{"timestamp_sec": 0.0, "joint_coordinates": "[]"}],
            "persona": "BEGINNER",
            "session_nonce": "test-nonce",
        },
    )

    assert res.status_code == 200
    body = res.json()
    assert body["session_id"] == 12345


def test_reattach_analysis_unsupported_exercise_returns_success_false():
    """ReattachAnalysis는 StartAnalysis와 거절 방식이 다르다(abort 아님, success=False) —
    이 REST 어댑터가 그 차이를 안 뭉개는지 확인한다."""
    client = _client()

    res = client.post(
        "/api/v1/internal/analysis/reattach",
        json={
            "session_id": 998,
            "exercise_id": 2,  # 미지원
            "persona": "BEGINNER",
            "initial_rep_count": 0,
            "elapsed_sec": 0.0,
            "session_nonce": "",
            "reference_poses": [],
        },
    )

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False


def test_stop_analysis_unknown_session_returns_success_false(monkeypatch):
    """세션이 없으면(보유기간 밖) success=False — ExerciseAnalysisService의 session-missing
    분기가 그대로 이 응답을 받는다."""
    monkeypatch.setattr(servicer_mod, "get_pool", lambda: _FakePool())
    client = _client()

    res = client.post(
        "/api/v1/internal/analysis/stop",
        json={"session_id": 987654321},
    )

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert "찾을 수 없습니다" in body["message"]


def test_extract_reference_data_rejects_remote_url():
    """원격(http/https) URL은 #192 미결정 항목이라 success=False로 거절 — abort는 아니다."""
    client = _client()

    res = client.post(
        "/api/v1/internal/analysis/extract-reference",
        json={"exercise_id": 1, "youtube_url": "https://youtube.com/watch?v=dummy"},
    )

    assert res.status_code == 200
    assert res.json()["success"] is False
