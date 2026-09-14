package com.shadowfit.service.report.llm;

import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.global.observability.WeeklyReportMetrics;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.report.WeeklyReport;
import com.shadowfit.model.report.WeeklyReportSource;
import com.shadowfit.service.report.WeeklySummaryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("주간 리포트 LLM 생성 — 어떤 경로로 끝나든 행은 종료 상태, 재호출은 전송 실패에만")
class WeeklyReportGenerationServiceTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 9, 7);

    private final WeeklyReportStore store = mock(WeeklyReportStore.class);
    private final WeeklySummaryService summaryService = mock(WeeklySummaryService.class);
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final WeeklyReportGenerationService service =
            new WeeklyReportGenerationService(store, summaryService, gemini, new WeeklyReportMetrics(new SimpleMeterRegistry()));

    private WeeklyReport pending;

    @BeforeEach
    void setUp() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(7L);
        pending = WeeklyReport.builder().id(1L).member(member).periodStart(WEEK).periodEnd(WEEK.plusWeeks(1))
                .summarySource(WeeklyReportSource.PENDING).build();
        when(store.findById(1L)).thenReturn(Optional.of(pending));
        when(store.fallBack(anyLong(), anyString(), any(), anyString())).thenReturn(true);
        when(store.completeWithLlm(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(gemini.isEnabled()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3.5-flash-lite");
    }

    @Test
    @DisplayName("검증 통과 → completeWithLlm(summary, cited, model, v1) + SENT")
    void ok_storesLlm() {
        when(summaryService.compute(7L, WEEK)).thenReturn(computation());
        when(gemini.generate(anyString(), anyString(), any(), anyDouble())).thenReturn(new GeminiResult(
                "{\"summary\":\"41회에서 58회로 늘었지만 싱크로율은 74.8에서 71.4로 내려갔어요. 4회차 이후 내려가는 패턴이에요.\","
                        + "\"cited_metrics\":[{\"name\":\"총 rep 수\",\"value\":58}]}",
                "gemini-3.5-flash-lite", 578, 150, "STOP"));

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.SENT);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store).completeWithLlm(eq(1L), summary.capture(), anyString(), eq("gemini-3.5-flash-lite"), eq(WeeklyReportPrompt.VERSION));
        assertThat(summary.getValue()).startsWith("41회에서");
        verify(store, never()).fallBack(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("검증 실패(없는 숫자) → fallBack(validation:…) + SENT — 재호출하지 않는다")
    void validationFails_fallsBack() {
        when(summaryService.compute(7L, WEEK)).thenReturn(computation());
        when(gemini.generate(anyString(), anyString(), any(), anyDouble())).thenReturn(new GeminiResult(
                "{\"summary\":\"rep 가 41.5% 늘었어요.\",\"cited_metrics\":[{\"name\":\"a\",\"value\":58}]}",
                "gemini-3.5-flash-lite", 578, 40, "STOP"));

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.SENT);

        verify(store).fallBack(eq(1L), eq("validation:text-unknown-number"), eq("gemini-3.5-flash-lite"), eq(WeeklyReportPrompt.VERSION));
        verify(store, never()).completeWithLlm(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("전송 실패(429·503·타임아웃·서킷) → RETRY, 행은 PENDING 그대로")
    void transportError_retries() {
        when(summaryService.compute(7L, WEEK)).thenReturn(computation());
        when(gemini.generate(anyString(), anyString(), any(), anyDouble()))
                .thenThrow(new GeminiTransportException("503", null));

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.RETRY);

        verify(store, never()).fallBack(anyLong(), anyString(), any(), anyString());
        verify(store, never()).completeWithLlm(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("거절(4xx·본문 없음) → fallBack(rejected) + SENT")
    void rejected_fallsBack() {
        when(summaryService.compute(7L, WEEK)).thenReturn(computation());
        when(gemini.generate(anyString(), anyString(), any(), anyDouble()))
                .thenThrow(new GeminiRejectedException("404 model gone"));

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.SENT);
        verify(store).fallBack(eq(1L), eq("rejected"), any(), anyString());
    }

    @Test
    @DisplayName("그 주에 기록이 없으면 LLM 을 부르지 않고 fallBack(no-record)")
    void emptyWeek_noLlmCall() {
        WeeklySummaryResponseDto empty = new WeeklySummaryResponseDto(WEEK, WEEK.plusWeeks(1),
                WeeklyTotalsDto.empty(), WeeklyTotalsDto.empty(), List.of("이번 주 기록이 없어요."));
        when(summaryService.compute(7L, WEEK)).thenReturn(new WeeklySummaryService.Computation(empty, List.of(), List.of()));

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.SENT);
        verify(gemini, never()).generate(anyString(), anyString(), any(), anyDouble());
        verify(store).fallBack(eq(1L), eq("no-record"), any(), anyString());
    }

    @Test
    @DisplayName("키가 없으면(LLM 비활성) fallBack(disabled) — 서비스는 그대로 선다(§9)")
    void disabled_fallsBack() {
        when(summaryService.compute(7L, WEEK)).thenReturn(computation());
        when(gemini.isEnabled()).thenReturn(false);

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.SENT);
        verify(gemini, never()).generate(anyString(), anyString(), any(), anyDouble());
        verify(store).fallBack(eq(1L), eq("disabled"), any(), anyString());
    }

    @Test
    @DisplayName("이미 종료 상태인 행(재배달·회수분) → 아무것도 안 하고 SENT")
    void terminalRow_isIdempotent() {
        pending.fallBack("no-record", null, "v1", java.time.LocalDateTime.now());

        assertThat(service.dispatch(1L)).isEqualTo(DispatchOutcome.SENT);
        verify(summaryService, never()).compute(anyLong(), any());
        verify(gemini, never()).generate(anyString(), anyString(), any(), anyDouble());
    }

    @Test
    @DisplayName("행이 없으면(회원 탈퇴 CASCADE) TERMINAL_FAILED")
    void missingRow_terminal() {
        when(store.findById(2L)).thenReturn(Optional.empty());
        assertThat(service.dispatch(2L)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
    }

    @Test
    @DisplayName("재시도 소진(giveUp) → fallBack(exhausted)")
    void giveUp_marksExhausted() {
        service.giveUp(1L);
        verify(store).fallBack(eq(1L), eq("exhausted"), eq("gemini-3.5-flash-lite"), eq(WeeklyReportPrompt.VERSION));
    }

    private static WeeklySummaryService.Computation computation() {
        WeeklyTotalsDto thisWeek = new WeeklyTotalsDto(4, 58, new BigDecimal("71.4"), new BigDecimal("72.9"), 3);
        WeeklyTotalsDto lastWeek = new WeeklyTotalsDto(3, 41, new BigDecimal("74.8"), new BigDecimal("75.1"), 3);
        WeeklySummaryResponseDto summary = new WeeklySummaryResponseDto(WEEK, WEEK.plusWeeks(1), thisWeek, lastWeek,
                List.of("이번 주 3일 동안 4번 운동했어요.", "총 58회로 지난주보다 17회 많아요.",
                        "싱크로율은 71.4점으로 지난주보다 3.4점 내려갔어요."));
        List<RepCurvePointDto> curve = List.of(
                new RepCurvePointDto(1, new BigDecimal("78.2"), 4), new RepCurvePointDto(4, new BigDecimal("70.1"), 4),
                new RepCurvePointDto(8, new BigDecimal("63.9"), 2));
        return new WeeklySummaryService.Computation(summary, curve, List.of());
    }
}
