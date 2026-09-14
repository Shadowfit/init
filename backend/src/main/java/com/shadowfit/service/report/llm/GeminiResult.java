package com.shadowfit.service.report.llm;

/** 한 번의 generateContent 결과 — 본문 텍스트(JSON 문자열)와 «누가 썼나·얼마 썼나». */
public record GeminiResult(String text, String model, Integer promptTokens, Integer outputTokens, String finishReason) {
}
