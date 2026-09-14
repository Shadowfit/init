package com.shadowfit.service.report.llm;

/** 우리 요청이 틀렸거나(4xx) 응답이 규격 밖이다 — 다시 보내도 같다 → 폴백. 상대의 건강 신호가 아니라 서킷은 안 센다. */
public class GeminiRejectedException extends RuntimeException {
    public GeminiRejectedException(String message) {
        super(message);
    }

    public GeminiRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
