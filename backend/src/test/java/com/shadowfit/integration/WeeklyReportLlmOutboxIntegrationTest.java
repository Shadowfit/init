package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.model.report.WeeklyReport;
import com.shadowfit.model.report.WeeklyReportSource;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.repository.report.WeeklyReportRepository;
import com.shadowfit.service.exercise.OutboxPublisher;
import com.shadowfit.service.report.WeeklySummaryService;
import com.shadowfit.service.report.llm.GeminiClient;
import com.shadowfit.service.report.llm.GeminiResult;
import com.shadowfit.service.report.llm.GeminiTransportException;
import com.shadowfit.service.report.llm.WeeklyReportOutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조회 → PENDING 행 + 아웃박스 → 주간 리포트 차선 발행기 → Gemini(mock) → 검증 → 행 종료 → 재조회.
 * (report-generation-llm.md §14-2 B-a+a-1, §5-2 안 A, §9)
 *
 * <p>바깥 세계만 가짜: Gemini = {@link GeminiClient} mock. 집계({@link WeeklySummaryService})도 mock 인 이유는
 * B층이 {@code JSON_TABLE} 이라 H2 에서 원리상 못 도는 것(18-testing-guide §2.4) — 그 쿼리는 race 프로파일 몫이고,
 * 여기서 묻는 것은 «이음새» 다: 행·아웃박스·차선 분리·검증·폴백·멱등.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:weekly_report_llm;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1"
})
@DisplayName("주간 리포트 LLM — 조회 시 lazy 발행 → 별도 차선 발행기 → 검증 → 저장 통합테스트")
class WeeklyReportLlmOutboxIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private WeeklyReportRepository weeklyReportRepository;
    @Autowired private OutboxPublisher defaultPublisher;
    @Autowired private WeeklyReportOutboxPublisher reportPublisher;
    @Autowired private com.shadowfit.service.report.llm.WeeklyReportStore store;

    @MockitoBean private GeminiClient gemini;
    @MockitoBean private WeeklySummaryService weeklySummaryService;

    private Member me;
    private LocalDate lastWeek;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        weeklyReportRepository.deleteAll();
        me = memberRepository.saveAndFlush(Member.builder().email("weekly-llm@test.com").username("weekly-llm")
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
        lastWeek = LocalDate.now().minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        when(gemini.isEnabled()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3.5-flash-lite");
        when(weeklySummaryService.getWeeklySummary(anyLong(), any())).thenReturn(computation().summary());
        when(weeklySummaryService.compute(anyLong(), any())).thenReturn(computation());
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        weeklyReportRepository.deleteAll();
        memberRepository.delete(me);
    }

    @Test
    @DisplayName("첫 조회: 템플릿 즉시 + PENDING 행 + GENERATE_WEEKLY_REPORT 한 건. 두 번째 조회는 행을 더 안 만든다")
    void firstRead_enqueuesOnce() throws Exception {
        read().andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").value(lastWeek.toString()))
                .andExpect(jsonPath("$.aiSummarySource").value("PENDING"))
                .andExpect(jsonPath("$.aiSummary").doesNotExist())
                .andExpect(jsonPath("$.summary.sentences[0]").value("이번 주 3일 동안 4번 운동했어요."));
        read().andExpect(status().isOk()).andExpect(jsonPath("$.aiSummarySource").value("PENDING"));

        List<WeeklyReport> rows = weeklyReportRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSummarySource()).isEqualTo(WeeklyReportSource.PENDING);
        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(OutboxEventType.GENERATE_WEEKLY_REPORT);
        assertThat(events.get(0).getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_TYPE_WEEKLY_REPORT);
        assertThat(events.get(0).getAggregateId()).isEqualTo(rows.get(0).getId());
        verify(gemini, never()).generate(anyString(), anyString(), any(), anyDouble());
    }

    @Test
    @DisplayName("기본 차선 발행기는 이 이벤트를 집지 않는다 — 주간 리포트 차선 tick 이 집어 LLM 문장을 저장하고 재조회에 실린다")
    void laneSeparation_thenLlmStored() throws Exception {
        when(gemini.generate(anyString(), anyString(), any(), anyDouble())).thenReturn(new GeminiResult(
                "{\"summary\":\"41회에서 58회로 늘었지만 싱크로율은 74.8에서 71.4로 내려갔어요. 4회차 이후 내려가는 패턴이에요.\","
                        + "\"cited_metrics\":[{\"name\":\"총 rep 수\",\"value\":58},{\"name\":\"rep 가중 싱크로율\",\"value\":71.4}]}",
                "gemini-3.5-flash-lite", 578, 150, "STOP"));
        read().andExpect(status().isOk());

        defaultPublisher.dispatchPending();
        assertThat(outboxRepository.findAll().get(0).getStatus()).as("기본 차선은 안 집는다").isEqualTo(OutboxStatus.PENDING);
        verify(gemini, never()).generate(anyString(), anyString(), any(), anyDouble());

        reportPublisher.dispatchPending();
        assertThat(outboxRepository.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(gemini, times(1)).generate(anyString(), anyString(), any(), anyDouble());
        WeeklyReport row = weeklyReportRepository.findAll().get(0);
        assertThat(row.getSummarySource()).isEqualTo(WeeklyReportSource.LLM);
        assertThat(row.getGenerationModel()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(row.getPromptVersion()).isEqualTo("v1");
        assertThat(row.getCitedMetrics()).contains("71.4");

        read().andExpect(status().isOk())
                .andExpect(jsonPath("$.aiSummarySource").value("LLM"))
                .andExpect(jsonPath("$.aiSummary").value(org.hamcrest.Matchers.startsWith("41회에서 58회로")))
                .andExpect(jsonPath("$.aiGeneratedAt").exists());

        // 다시 tick 이 돌아도(회수분 시뮬레이션) 호출이 늘지 않는다 — 종료 상태 행은 멱등
        reportPublisher.dispatchPending();
        verify(gemini, times(1)).generate(anyString(), anyString(), any(), anyDouble());

        // 종료 상태 전이는 «PENDING 일 때만» — 다른 발행기가 뒤늦게 폴백을 쓰려 해도 0행, LLM 결과가 덮이지 않는다
        assertThat(store.fallBack(row.getId(), "late-loser", "x", "v1")).isFalse();
        assertThat(weeklyReportRepository.findById(row.getId()).orElseThrow().getSummarySource()).isEqualTo(WeeklyReportSource.LLM);
    }

    @Test
    @DisplayName("검증에 걸린 출력(없는 숫자)은 저장하지 않고 TEMPLATE_FALLBACK — 응답은 템플릿 문장만, 재호출 없음")
    void invalidOutput_fallsBack() throws Exception {
        when(gemini.generate(anyString(), anyString(), any(), anyDouble())).thenReturn(new GeminiResult(
                "{\"summary\":\"rep 가 41.5% 늘었어요.\",\"cited_metrics\":[{\"name\":\"총 rep 수\",\"value\":58}]}",
                "gemini-3.5-flash-lite", 578, 40, "STOP"));
        read();

        reportPublisher.dispatchPending();

        WeeklyReport row = weeklyReportRepository.findAll().get(0);
        assertThat(row.getSummarySource()).isEqualTo(WeeklyReportSource.TEMPLATE_FALLBACK);
        assertThat(row.getFallbackReason()).isEqualTo("validation:text-unknown-number");
        assertThat(row.getSummary()).isNull();
        assertThat(outboxRepository.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
        read().andExpect(jsonPath("$.aiSummarySource").value("TEMPLATE_FALLBACK"))
                .andExpect(jsonPath("$.aiSummary").doesNotExist())
                .andExpect(jsonPath("$.summary.sentences").isArray());
        verify(gemini, times(1)).generate(anyString(), anyString(), any(), anyDouble());
    }

    @Test
    @DisplayName("전송 실패(503)는 RETRY — 행은 PENDING 유지, 아웃박스는 백오프. 소진되면 발행기 훅이 exhausted 로 닫는다")
    void transportFailure_retries_thenExhaustedClosesRow() throws Exception {
        when(gemini.generate(anyString(), anyString(), any(), anyDouble()))
                .thenThrow(new GeminiTransportException("503 high demand", null));
        read();

        reportPublisher.dispatchPending();
        OutboxEvent event = outboxRepository.findAll().get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt()).isNotNull();
        assertThat(weeklyReportRepository.findAll().get(0).getSummarySource()).isEqualTo(WeeklyReportSource.PENDING);

        // 재시도 한도 직전까지 온 행을 흉내 낸다 — 다음 실패가 마지막
        event.setRetryCount(12);
        event.setNextRetryAt(null);
        outboxRepository.saveAndFlush(event);
        reportPublisher.dispatchPending();

        assertThat(outboxRepository.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.FAILED);
        WeeklyReport row = weeklyReportRepository.findAll().get(0);
        assertThat(row.getSummarySource()).isEqualTo(WeeklyReportSource.TEMPLATE_FALLBACK);
        assertThat(row.getFallbackReason()).isEqualTo("exhausted");
    }

    /**
     * #759 회귀 고정. dispatch() 가 던진 예외(여기선 집계가 NPE)는 예전엔 행을 PROCESSING 으로 두고 lease 회수에
     * 맡겼다 — 회수는 retryCount 를 안 올려 리포트가 영원히 «준비 중» 이었다. 이제 RETRY 로 세고 소진되면 발행기 훅이 닫는다.
     */
    @Test
    @DisplayName("dispatch 가 던진 영구 예외도 RETRY 로 세고, 소진되면 발행기 훅이 exhausted 로 닫는다 — 영원히 PENDING 이 아니다")
    void permanentException_countsAsRetry_thenExhaustedClosesRow() throws Exception {
        read();
        when(weeklySummaryService.compute(anyLong(), any())).thenThrow(new NullPointerException("집계 버그 흉내"));

        reportPublisher.dispatchPending();
        OutboxEvent event = outboxRepository.findAll().get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt()).isNotNull();

        event.setRetryCount(12);
        event.setNextRetryAt(null);
        outboxRepository.saveAndFlush(event);
        reportPublisher.dispatchPending();

        assertThat(outboxRepository.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.FAILED);
        WeeklyReport row = weeklyReportRepository.findAll().get(0);
        assertThat(row.getSummarySource()).isEqualTo(WeeklyReportSource.TEMPLATE_FALLBACK);
        assertThat(row.getFallbackReason()).isEqualTo("exhausted");
    }

    @Test
    @DisplayName("이번 주·미래 주는 400(R002) — LLM 문장은 끝난 주에만")
    void currentWeek_rejected() throws Exception {
        mockMvc.perform(get("/reports/weekly-report").param("week", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("아직 끝나지 않은 주의 리포트는 만들 수 없습니다"));
        assertThat(weeklyReportRepository.findAll()).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions read() throws Exception {
        return mockMvc.perform(get("/reports/weekly-report").header("Authorization", "Bearer " + token()));
    }

    private String token() {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(me.getEmail()).role(me.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }

    private WeeklySummaryService.Computation computation() {
        WeeklyTotalsDto thisWeek = new WeeklyTotalsDto(4, 58, new BigDecimal("71.4"), new BigDecimal("72.9"), 3);
        WeeklyTotalsDto last = new WeeklyTotalsDto(3, 41, new BigDecimal("74.8"), new BigDecimal("75.1"), 3);
        WeeklySummaryResponseDto summary = new WeeklySummaryResponseDto(lastWeek, lastWeek.plusWeeks(1), thisWeek, last,
                List.of("이번 주 3일 동안 4번 운동했어요.", "총 58회로 지난주보다 17회 많아요.",
                        "싱크로율은 71.4점으로 지난주보다 3.4점 내려갔어요."));
        List<RepCurvePointDto> curve = List.of(new RepCurvePointDto(1, new BigDecimal("78.2"), 4),
                new RepCurvePointDto(4, new BigDecimal("70.1"), 4), new RepCurvePointDto(8, new BigDecimal("63.9"), 2));
        return new WeeklySummaryService.Computation(summary, curve, List.of());
    }
}
