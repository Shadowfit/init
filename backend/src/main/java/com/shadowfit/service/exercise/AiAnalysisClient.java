package com.shadowfit.service.exercise;

import java.util.List;
import java.util.function.Consumer;

/**
 * Spring → AI 서버로 나가는 요청 방향 RPC 4개(exercise.proto)의 프로토콜 무관 계약.
 *
 * <p><b>4개를 같은 모양으로 통일하지 않는다.</b> 지금 {@code ExerciseAnalysisService}가 이미
 * RPC마다 다른 호출 계약을 쓰고 있고, 각각 이유가 문서화돼 있다 — gRPC/WebClient 어느 구현체든
 * 이 계약을 그대로 지켜야 한다(docs/decisions/grpc-webclient-empirical-comparison.md §8.4):
 *
 * <ul>
 *   <li>{@link #extractReferenceData}·{@link #startAnalysis} — 호출자가 결과를 기다리지
 *       않는다(fire-and-forget). 결과는 {@code onResult} 콜백으로만 나중에 보고된다 —
 *       서킷브레이커 기록·세션 FAILED 처리 등은 호출자가 그 콜백 안에서 한다.</li>
 *   <li>{@link #reattachAnalysis}·{@link #stopAnalysis} — 호출자가 응답을 반드시 알아야
 *       다음을 정한다(재부착 가능 여부, 아웃박스 행 상태). <b>의도적으로 블로킹</b>이다 —
 *       두 호출부 모두 "블로킹 비용이 사실상 0인 스레드"(사용자 요청 스레드·아웃박스 발행기
 *       전용 스레드)에서만 불린다는 전제가 이미 깔려 있다. 논블로킹으로 바꿔도 얻을 게
 *       없다고 이미 판단된 지점이라, 억지로 {@code Mono}/콜백으로 바꾸는 게 오히려 과설계다.</li>
 * </ul>
 *
 * <p>라우팅 키는 세션 무관 호출(예: 관리자 배치인 {@code extractReferenceData})이 있어
 * {@code sessionId}로 고정하지 않고 {@code routingKey}로 둔다 — 그 경우 호출자가
 * {@code exerciseId} 등 세션과 무관한 값을 넣는다(스티키가 필요 없어 "결정적으로 아무 채널이나
 * 고르는" 용도).
 */
public interface AiAnalysisClient {

    /**
     * [STEP 1] 유튜브 영상에서 기준 좌표를 추출하도록 AI에 요청한다. 관리자용 배치 작업이라
     * 세션과 무관하다 — {@code routingKey}에는 보통 {@code exerciseId}를 넣는다.
     */
    void extractReferenceData(long routingKey, ExtractCommand command,
                               Consumer<AiCallOutcome<ExtractResult>> onResult);

    /**
     * [STEP 3] 세션을 시작하며 기준 좌표를 AI로 전송한다. 호출부(세션 INSERT 응답)는 이미
     * 끝난 뒤에 비동기로 발사된다 — {@code onResult}는 실패 시 세션을 FAILED로 되돌리는 등
     * 후속 조치를 위한 콜백이지, 호출자가 이 메서드 호출 자체를 기다리기 위한 것이 아니다.
     */
    void startAnalysis(long routingKey, AnalyzeCommand command,
                        Consumer<AiCallOutcome<AnalyzeResult>> onResult);

    /**
     * [STEP 3-R] 이미 IN_PROGRESS인 세션의 AI 분석 상태를 되살린다. 호출자가 "이어할 수
     * 있는지"를 알아야 다음을 정하므로 블로킹이다.
     */
    AiCallOutcome<ReattachResult> reattachAnalysis(long routingKey, ReattachCommand command);

    /**
     * [STEP 4] AI 분석 중단을 알린다. 아웃박스 발행기가 결과를 알아야 행 상태(SENT/재시도/
     * 터미널)를 정하므로 블로킹이다.
     */
    AiCallOutcome<StopResult> stopAnalysis(long routingKey, StopCommand command);

    // ------------------------------------------------------------
    // 공용
    // ------------------------------------------------------------

    /** 기준/참조 좌표 한 프레임. PoseDataRequest 중 이 4개 RPC가 실제로 채우는 두 필드만 담는다. */
    record PoseRef(double timestampSec, String jointCoordinates) {}

    // ------------------------------------------------------------
    // ExtractReferenceData
    // ------------------------------------------------------------

    record ExtractCommand(long exerciseId, String youtubeUrl) {}

    record ExtractResult(boolean success, long exerciseId) {}

    // ------------------------------------------------------------
    // StartAnalysis
    // ------------------------------------------------------------

    record AnalyzeCommand(
            long exerciseId,
            long sessionId,
            String referenceSource,
            List<PoseRef> referencePoses,
            String persona,
            String sessionNonce
    ) {}

    record AnalyzeResult(long sessionId) {}

    // ------------------------------------------------------------
    // ReattachAnalysis
    // ------------------------------------------------------------

    record ReattachCommand(
            long sessionId,
            long exerciseId,
            String persona,
            int initialRepCount,
            double elapsedSec,
            String sessionNonce,
            List<PoseRef> referencePoses
    ) {}

    record ReattachResult(boolean success, int repCount, boolean alreadyActive, String message) {}

    // ------------------------------------------------------------
    // StopAnalysis
    // ------------------------------------------------------------

    record StopCommand(long sessionId) {}

    record StopResult(boolean success, String message) {}
}
