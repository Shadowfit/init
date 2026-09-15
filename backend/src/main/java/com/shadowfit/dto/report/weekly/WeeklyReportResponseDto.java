package com.shadowfit.dto.report.weekly;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.report.WeeklyReportSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code GET /reports/weekly-report} — 끝난 주 하나의 리포트. {@code summary} 는 항상 있다(조회 시 계산).
 * {@code aiSummary} 는 {@code aiSummarySource == LLM} 일 때만 값이 있고, PENDING/TEMPLATE_FALLBACK 이면 null —
 * 화면은 그때 {@code summary.sentences()} 를 그대로 쓴다(report-generation-llm.md §9).
 */
public record WeeklyReportResponseDto(
        // 날짜 직렬화는 group·notification DTO 와 같이 필드에 명시 — 테스트 프로파일엔 write-dates-as-timestamps 가 없다
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate periodEnd,
        WeeklySummaryResponseDto summary,
        String aiSummary,
        WeeklyReportSource aiSummarySource,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime aiGeneratedAt
) {
}
