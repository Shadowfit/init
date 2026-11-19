package com.shadowfit.model.exercise;

/**
 * «미인정 시도» 의 사유 코드 (V35, proto {@code RejectedAttemptRequest.reason}).
 *
 * <p>AI 가 보내는 문자열과 이름이 같아야 한다 — {@code valueOf} 로 받는다. 모르는 값은 입구
 * ({@code RejectedAttemptService})에서 INVALID_ARGUMENT 로 거절한다. 컬럼은 VARCHAR(32) 라 사유가 늘면 여기만 더하면 된다.
 */
public enum RejectedAttemptReason {
    /** 바닥 자세가 확인되지 않음 — 덜 앉았거나 바닥에서 너무 빨리 일어났다 */
    DEPTH_NOT_CONFIRMED,
    /** 두 발이 앞뒤로 벌어진 런지 자세로 인식됐다 */
    SPLIT_STANCE
}
