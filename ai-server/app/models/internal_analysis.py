"""Spring → AI REST 계약 (docs/decisions/grpc-webclient-empirical-comparison.md §8).

`exercise.proto`의 요청 방향 RPC 4개(ExtractReferenceData/StartAnalysis/ReattachAnalysis/
StopAnalysis)를 REST로 미러링한다 — 필드를 안 늘린다(순수 프로토콜 비교가 목적이라, 계약을
동시에 바꾸면 무엇 때문에 결과가 달라졌는지 못 가른다). 필드명은 이 저장소의 다른 Pydantic
모델(`app/models/pose.py`)과 같은 관례(snake_case, proto 필드명 그대로)를 따른다 — Spring
쪽 `WebClientAiAnalysisClient`가 이 관례에 맞춰 JSON을 snake_case로 직렬화한다.
"""

from pydantic import BaseModel


class PoseRefDto(BaseModel):
    timestamp_sec: float
    joint_coordinates: str


class ExtractCommand(BaseModel):
    exercise_id: int
    youtube_url: str


class ExtractResult(BaseModel):
    success: bool
    exercise_id: int


class AnalyzeCommand(BaseModel):
    exercise_id: int
    session_id: int
    reference_source: str
    reference_poses: list[PoseRefDto] = []
    persona: str = ""
    session_nonce: str = ""


class AnalyzeResult(BaseModel):
    session_id: int


class ReattachCommand(BaseModel):
    session_id: int
    exercise_id: int
    persona: str = ""
    initial_rep_count: int = 0
    elapsed_sec: float = 0.0
    session_nonce: str = ""
    reference_poses: list[PoseRefDto] = []


class ReattachResult(BaseModel):
    success: bool
    rep_count: int
    already_active: bool
    message: str


class StopCommand(BaseModel):
    session_id: int


class StopResult(BaseModel):
    success: bool
    message: str


# --- nested 계약 (5차 라운드, docs/decisions/grpc-webclient-native-rest-round.md §2-3) ---
#
# `joint_coordinates` 를 문자열이 아니라 중첩 JSON 그대로 받는다. `list[dict]` 까지만 —
# 랜드마크 하나하나를 pydantic 모델로 검증하면 proto 가 안 내는(문자열을 복사만 하는) 검증
# 비용을 이 팔에만 얹어 C−D 뺄셈이 «계약» 과 «검증» 둘을 동시에 재게 된다(§7 ⑤).


class PoseRefNestedDto(BaseModel):
    timestamp_sec: float
    joint_coordinates: list[dict]


class AnalyzeNestedCommand(BaseModel):
    exercise_id: int
    session_id: int
    reference_source: str
    reference_poses: list[PoseRefNestedDto] = []
    persona: str = ""
    session_nonce: str = ""


class ReattachNestedCommand(BaseModel):
    session_id: int
    exercise_id: int
    persona: str = ""
    initial_rep_count: int = 0
    elapsed_sec: float = 0.0
    session_nonce: str = ""
    reference_poses: list[PoseRefNestedDto] = []
