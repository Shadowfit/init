"""API 라우터 통합."""

from fastapi import APIRouter

from app.api.endpoints import internal_analysis, pose, sync, video
from app.observability import frame_path

api_router = APIRouter(prefix="/api/v1")
api_router.include_router(pose.router)
api_router.include_router(sync.router)
api_router.include_router(video.router)
# Spring→AI 요청 방향 RPC 4개의 REST 미러(실측 비교용, docs/decisions/
# grpc-webclient-empirical-comparison.md §8) — INTERNAL_API_TOKEN으로 인증한다(auth.py).
api_router.include_router(internal_analysis.router)
# 프레임 경로 계측 읽기(§12). 계측이 꺼져 있어도 라우트는 산다 — «꺼져 있음» 을 확인하는
# 것도 측정 절차의 일부다. /pose 와 같은 인증을 탄다.
api_router.include_router(frame_path.router)
