"""전 테스트 공통 설정."""
import pytest

from app.config import settings


@pytest.fixture(autouse=True)
def _no_late_complete_retry_by_default(monkeypatch):
    """CompleteAnalysis «느린 재시도»(#799)를 기본으로 끈다.

    켜 두면 즉시 재시도를 다 쓰는 시험이 30초 뒤에 울리는 **실제 타이머**를 남기고, 그 타이머가 다른 시험이 도는 중에 진짜 gRPC
    연결을 시도한다 — 시험 사이로 새는 비결정적 소음이다. 느린 재시도를 보는 시험(`test_complete_late_retry.py`)만 이 값을 올려 쓴다.
    """
    monkeypatch.setattr(settings, "COMPLETE_RETRY_WINDOW_SEC", 0)
