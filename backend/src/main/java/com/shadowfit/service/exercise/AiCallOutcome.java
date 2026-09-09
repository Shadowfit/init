package com.shadowfit.service.exercise;

/**
 * AI 호출 결과를 프로토콜 무관하게 정규화한다.
 *
 * <p>gRPC {@code StatusRuntimeException}(status code)과 WebClient
 * {@code WebClientResponseException}(HTTP status)은 실패를 표현하는 어휘가 서로 다르다.
 * 각 {@link AiAnalysisClient} 구현체가 자기 프로토콜의 실패를 이 3분류 중 하나로 변환해서
 * 돌려주고, 서킷브레이커 기록·업무 반응(세션 FAILED 처리 등)은 전부 호출자
 * ({@code ExerciseAnalysisService})가 이 결과를 보고 결정한다 — 구현체는 전송 + 정규화만
 * 맡는다. 두 구현체가 같은 업무 로직을 각자 복제하면 미묘하게 어긋나기 쉽다
 * (docs/decisions/grpc-webclient-empirical-comparison.md §8.4).
 */
public sealed interface AiCallOutcome<T> {

    record Success<T>(T value) implements AiCallOutcome<T> {}

    /**
     * 요청 자체가 틀렸다(gRPC {@code INVALID_ARGUMENT}급 — 예: 분석기 없는 종목).
     * 상대는 건강하다는 뜻이라 서킷브레이커 실패 집계에 안 넣는 게 원칙이다 — 다만 이
     * 구분을 실제로 쓸지는 호출부마다 다르다({@code StartAnalysis}만 구분해서
     * {@code releasePermission()}을 쓰고, 나머지 3개는 원래도 구분 없이 실패로 기록했다).
     * 그래서 {@code cause}도 {@link TransientFailure}와 대칭으로 들고 있다 — 구분을 안 쓰는
     * 호출부는 이걸 그대로 {@code cb.onError(...)}에 넘기면 기존 동작과 같아진다.
     */
    record ClientRejected<T>(String message, Throwable cause) implements AiCallOutcome<T> {}

    /**
     * 연결 실패·타임아웃·5xx 등 — 상대가 아프다는 신호. 서킷브레이커에 실패로 기록한다.
     */
    record TransientFailure<T>(String message, Throwable cause) implements AiCallOutcome<T> {}
}
