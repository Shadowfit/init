package com.shadowfit.service.report.llm;

/** 상대에 닿지 못했거나 상대가 지금 못 받는다(타임아웃·5xx·429·503·서킷 OPEN) — 다시 보내면 될 수 있다 → RETRY. */
public class GeminiTransportException extends RuntimeException {
    public GeminiTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
