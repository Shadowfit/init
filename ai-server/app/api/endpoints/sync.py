"""DTW sync-rate API plus onboarding guidance.

**`GET /onboarding-guide` 는 2026-08-24 부터 호출자가 있다** — 프론트
`services/aiService.ts` 의 `getOnboardingGuide()` 를 `(tabs)/index.tsx`·`(tabs)/exercise.tsx`
둘 다 부른다(#292 해결 — 정본을 서버로 통일, 값은 90° 측면·2~3m). 지우지 않기로 한
#293 결정을 부분적으로 뒤집은 것 — 아래 `POST ""`(sync-rate)는 여전히 미연결이다.

🔴 `POST ""` 는 저장소 안에 **호출자가 없다** (#293, 2026-08-22 확인) — 이건 그대로다.

- Spring 은 AI 를 gRPC 로만 부른다 — `backend/src` 전체에서 AI 로 가는 HTTP 호출 0건
- 프론트가 AI 를 부르는 곳은 `services/aiService.ts` 의 `POST /pose`·`GET /sync/onboarding-guide` 뿐
- `ai-server/tests` 도 `POST ""` 라우트는 안 탄다 (core 모듈을 직접 부른다)

**실시간 싱크로율의 정본은 여기가 아니다.** `squat_analyzer.py` 가 `compute_sync_rate` 를
직접 부르고 그 값이 gRPC `SavePoseDataBatch` 로 간다 — 같은 함수를 부르는 두 입구인데
한쪽만 쓰인다. `classify_sync_visual_cue` / `classify_sync_haptic_cue` 와
`SyncVisualCue` / `SyncHapticCue` (초록/주황/빨강 존 + 진동 패턴)는 gRPC 경로에 아예 없는
개념이라 #193(피드백 템플릿) 재료로 남겨둔 상태 — `POST ""` 를 연결할지는 그 결정이 먼저다.
"""

from fastapi import APIRouter

from app.core.dtw_calculator import (
    classify_sync_haptic_cue,
    classify_sync_visual_cue,
    compute_dtw_distance,
    compute_sync_rate,
)
from app.models.sync import (
    OnboardingGuideItem,
    OnboardingGuideResponse,
    SyncRequest,
    SyncResponse,
)

router = APIRouter(prefix="/sync", tags=["sync"])


@router.post("", response_model=SyncResponse, deprecated=True)
async def calculate_sync_rate(req: SyncRequest) -> SyncResponse:
    """Compare reference and user angle sequences and return UI-ready cues.

    ⚠️ 호출자 없음 (#293). 상태가 없다 — 세션도 rep 도 모르고 넣은 두 배열만 비교한다.
    실시간 경로는 `squat_analyzer` → gRPC 를 쓴다.
    """
    sync_rate = compute_sync_rate(req.reference_angles, req.user_angles)
    dtw_distance = compute_dtw_distance(req.reference_angles, req.user_angles)

    return SyncResponse(
        sync_rate=sync_rate,
        dtw_distance=dtw_distance,
        visual_cue=classify_sync_visual_cue(sync_rate),
        haptic_cue=classify_sync_haptic_cue(sync_rate),
    )


@router.get("/onboarding-guide", response_model=OnboardingGuideResponse)
async def get_onboarding_guide() -> OnboardingGuideResponse:
    """Return camera setup guidance shown on the home tab and the exercise screen.

    `step`/전체 `title` 필드 이름은 예전 온보딩 마법사 단계 자리에서 남은 이름인데,
    실제 온보딩 5단계(`(onboarding)/index.tsx`)엔 촬영 가이드 단계가 없다 — 지금 실제
    소비자는 `(tabs)/index.tsx`(홈)·`(tabs)/exercise.tsx`(운동 화면 접기/펴기 패널) 둘이고
    둘 다 `items`만 쓴다. 값(90도 측면·2~3m)은 #292 에서 서버를 정본으로 confirm 됐다.
    """
    return OnboardingGuideResponse(
        step=4,
        title="촬영 가이드",
        items=[
            OnboardingGuideItem(
                key="angle",
                title="각도",
                body="카메라는 몸 옆 90도 측면에 두고, 전신이 한 평면에서 보이게 촬영합니다.",
            ),
            OnboardingGuideItem(
                key="distance",
                title="거리",
                body="카메라와 2~3m 정도 거리를 두고 머리부터 발끝까지 화면 안에 모두 들어오게 맞춥니다.",
            ),
            OnboardingGuideItem(
                key="lighting",
                title="조명",
                body="역광을 피하고 정면 또는 측면에서 밝게 비춰 관절이 또렷하게 보이게 합니다.",
            ),
            OnboardingGuideItem(
                key="mirror",
                title="거울 주의",
                body="거울이나 반사체가 프레임에 들어오면 사람을 중복 인식할 수 있어 가능한 한 피합니다.",
            ),
        ],
    )
