"""Spring → AI 요청 방향 RPC 4개의 REST 미러 (docs/decisions/grpc-webclient-empirical-comparison.md §8).

`ExerciseServicer`(gRPC)의 메서드를 **그대로 in-process 호출**한다 — 비즈니스 로직을 복제하지
않는다. protobuf 메시지는 네트워크 없이 순수 파이썬 객체로 조립·해석되므로, REST 요청을 받아
같은 proto 메시지를 만들어 기존 서비서에 넘기고 응답을 다시 JSON으로 바꾸는 얇은 어댑터로 충분하다.

`context.abort()`(StartAnalysis가 분석기 없는 종목을 거절할 때 씀)는 실제 gRPC 컨텍스트가 예외를
일으켜 핸들러를 중단시키는 것과 같은 모양으로 흉내낸다 — `_FakeContext.abort()`가 예외를 던지고
여기서 잡아 HTTP 400으로 옮긴다. 이 라우터가 인증받는 방식(INTERNAL_API_TOKEN)은
`app/middleware/auth.py`를 볼 것 — `/pose`가 쓰는 AI_PUBLIC_TOKEN(앱 번들 노출값)과 다르다.
"""

import exercise_pb2
from fastapi import APIRouter, HTTPException

from app.grpc.exercise_servicer import ExerciseServicer
from app.models.internal_analysis import (
    AnalyzeCommand,
    AnalyzeResult,
    ExtractCommand,
    ExtractResult,
    PoseRefDto,
    ReattachCommand,
    ReattachResult,
    StopCommand,
    StopResult,
)

router = APIRouter(prefix="/internal/analysis", tags=["Spring→AI REST 미러 (실측 비교용)"])

# 서비서는 상태가 없다(전부 get_registry()/get_pool() 모듈 전역을 통해서만 상태를 본다) —
# 인스턴스 하나를 공유해도 gRPC 서버가 쓰는 인스턴스와 완전히 같은 동작이다.
_servicer = ExerciseServicer()


class _AbortSignal(Exception):
    """`grpc.ServicerContext.abort()`가 실제로 하는 일(예외로 핸들러를 중단)을 흉내낸다."""

    def __init__(self, code, details: str):
        super().__init__(details)
        self.code = code
        self.details = details


class _FakeContext:
    """이 4개 RPC 중 `abort()`를 부르는 건 StartAnalysis뿐이다 — 그거 하나만 지원하면 된다."""

    def abort(self, code, details):
        raise _AbortSignal(code, details)


def _to_pose_data_requests(items: list[PoseRefDto]):
    return [
        exercise_pb2.PoseDataRequest(timestamp_sec=p.timestamp_sec, joint_coordinates=p.joint_coordinates)
        for p in items
    ]


@router.post("/extract-reference", response_model=ExtractResult)
def extract_reference_data(command: ExtractCommand) -> ExtractResult:
    request = exercise_pb2.ExtractRequest(
        exercise_id=command.exercise_id, youtube_url=command.youtube_url
    )
    response = _servicer.ExtractReferenceData(request, _FakeContext())
    return ExtractResult(success=response.success, exercise_id=response.exercise_id)


@router.post("/start", response_model=AnalyzeResult)
def start_analysis(command: AnalyzeCommand) -> AnalyzeResult:
    request = exercise_pb2.AnalyzeRequest(
        exercise_id=command.exercise_id,
        session_id=command.session_id,
        reference_source=command.reference_source,
        reference_poses=_to_pose_data_requests(command.reference_poses),
        persona=command.persona,
        session_nonce=command.session_nonce,
    )
    try:
        response = _servicer.StartAnalysis(request, _FakeContext())
    except _AbortSignal as e:
        # gRPC 쪽 INVALID_ARGUMENT와 대칭 — Spring GrpcAiAnalysisClient.classify()가 이 코드를
        # ClientRejected로 분류하는 것처럼, WebClientAiAnalysisClient는 HTTP 400을 같은 값으로 본다.
        raise HTTPException(status_code=400, detail=e.details) from e
    return AnalyzeResult(session_id=response.session_id)


@router.post("/reattach", response_model=ReattachResult)
def reattach_analysis(command: ReattachCommand) -> ReattachResult:
    request = exercise_pb2.ReattachRequest(
        session_id=command.session_id,
        exercise_id=command.exercise_id,
        reference_poses=_to_pose_data_requests(command.reference_poses),
        persona=command.persona,
        initial_rep_count=command.initial_rep_count,
        elapsed_sec=command.elapsed_sec,
        session_nonce=command.session_nonce,
    )
    response = _servicer.ReattachAnalysis(request, _FakeContext())
    return ReattachResult(
        success=response.success,
        rep_count=response.rep_count,
        already_active=response.already_active,
        message=response.message,
    )


@router.post("/stop", response_model=StopResult)
def stop_analysis(command: StopCommand) -> StopResult:
    request = exercise_pb2.StopRequest(session_id=command.session_id)
    response = _servicer.StopAnalysis(request, _FakeContext())
    return StopResult(success=response.success, message=response.message)
