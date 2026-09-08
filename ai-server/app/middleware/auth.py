"""Bearer-token 인증 미들웨어.

분기 H2 (프론트 → AI 직결) 채택으로 `POST /pose` 가 외부 노출되므로
모든 인입 HTTP 요청에 `Authorization: Bearer <AI_PUBLIC_TOKEN>` 헤더를 강제한다.

⚠️ **gRPC 와 다른 토큰을 검증한다** (2026-08-09, 이슈 #134 / decisions/ai-auth-token-flow.md ㄱ).
예전엔 gRPC 측 `AuthInterceptor` 와 **같은 값**(`INTERNAL_API_TOKEN`)을 봤는데, 그 값이
프론트 번들(`EXPO_PUBLIC_`)에 인라인돼 배포되므로 앱에서 추출한 토큰으로 Spring 내부
gRPC(`SavePoseDataBatch` 등)까지 칠 수 있었다. 값을 나눠 그 횡단을 끊는다.

- `AI_PUBLIC_TOKEN`  → 이 미들웨어(HTTP). **클라이언트에 배포되는 값**
- `INTERNAL_API_TOKEN` → gRPC 인터셉터 + Spring 콜백. **서버 밖으로 나가지 않는 값**

미설정이면 모든 요청이 401 이다(fail-closed). 프론트는 토큰이 없으면 폴링 자체를
시작하지 않는다(`exercise.tsx`).
"""

from __future__ import annotations

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.types import ASGIApp

from app.config import settings

# 인증 우회 경로 — 헬스체크, Swagger 문서, OpenAPI 스펙, 그리고 지표(#151)
#
# 🔴 /metrics 를 안 열면 Prometheus 가 401 을 받고, 그러면 **영원히 DOWN 인 타깃**이 생긴다 —
#    prometheus.yml 이 «타깃만 적으면 관측 스택이 고장난 것처럼 보인다» 고 경고한 그 상태다.
#    지표가 안 새는 근거는 인증이 아니라 **네트워크 경계**다: prod compose 는 AI 포트를 호스트에
#    매핑하지 않고, 스크레이프는 같은 도커 네트워크 안에서만 풀린다(Spring 의 9090 과 같은 방식).
PUBLIC_PATHS: frozenset[str] = frozenset(
    {"/health", "/metrics", "/docs", "/redoc", "/openapi.json"}
)

# Spring → AI 요청 방향 RPC 4개의 REST 미러(실측 비교용, docs/decisions/
# grpc-webclient-empirical-comparison.md §8.2). 이 경로는 AI_PUBLIC_TOKEN(앱 번들에 배포되는
# 값)이 아니라 INTERNAL_API_TOKEN(서버 밖으로 안 나가는 값)으로 지킨다 — 그대로 방치하면
# #134/#230이 막았던 구멍이 이 4개 RPC에서 재발한다: 앱 번들에서 추출 가능한 토큰만으로
# 세션 시작·재부착까지 칠 수 있게 된다. gRPC 쪽 AuthInterceptor가 같은 값으로 지키는 것과
# 대칭이다.
INTERNAL_TOKEN_PREFIX = "/api/v1/internal/analysis"


class InternalAuthMiddleware(BaseHTTPMiddleware):
    def __init__(self, app: ASGIApp, public_paths: frozenset[str] = PUBLIC_PATHS):
        super().__init__(app)
        self._public_paths = public_paths

    async def dispatch(self, request: Request, call_next) -> Response:
        # CORS preflight (OPTIONS) 와 공개 경로는 인증 우회
        # CORSMiddleware 가 먼저 처리해 여기까지 안 오는 게 정상이지만 방어적으로 둠
        if request.method == "OPTIONS" or request.url.path in self._public_paths:
            return await call_next(request)

        if request.url.path.startswith(INTERNAL_TOKEN_PREFIX):
            return await self._check_bearer(request, call_next, settings.INTERNAL_API_TOKEN)

        return await self._check_bearer(request, call_next, settings.AI_PUBLIC_TOKEN)

    @staticmethod
    async def _check_bearer(request: Request, call_next, expected_token: str) -> Response:
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return JSONResponse(
                status_code=401, content={"detail": "Missing bearer token"}
            )

        token = auth_header[len("Bearer ") :]
        if not expected_token or token != expected_token:
            return JSONResponse(
                status_code=401, content={"detail": "Invalid token"}
            )

        return await call_next(request)
