"""API 라우터 통합."""

from fastapi import APIRouter

from app.api.endpoints import pose, sync
from app.observability import frame_path

api_router = APIRouter(prefix="/api/v1")
api_router.include_router(pose.router)
api_router.include_router(sync.router)
# /video/analyze 는 제거했다(2026-11): 호출자 0건(#293)인데 크기 제한 없는 업로드를 받고 async 핸들러에서
# 블로킹 추론을 돌려 이벤트 루프를 막았다. 기준 좌표 추출의 정본은 gRPC ExtractReferenceData 다.
# 프레임 경로 계측 읽기(§12). 계측이 꺼져 있어도 라우트는 산다 — «꺼져 있음» 을 확인하는
# 것도 측정 절차의 일부다. /pose 와 같은 인증을 탄다.
api_router.include_router(frame_path.router)
