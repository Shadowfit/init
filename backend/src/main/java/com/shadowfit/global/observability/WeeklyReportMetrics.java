package com.shadowfit.global.observability;

import com.shadowfit.service.report.WeeklySentenceRuleId;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 주간 요약(«A층·B층» 집계 + 규칙 문장)의 관측 지표.
 *
 * <p>설계: {@code report-generation-llm.md} §13-5. 세 개를 잰다:
 * <ul>
 *   <li>{@link #queryLatency} — 조회 1건의 지연. B층은 LLM 없이도 세션마다 JSON_TABLE 을
 *       펼치는 조회라, 세션이 많은 회원에서 얼마나 느려지는지가 «캐시/저장이 필요한가»의
 *       근거다(설계 §13-0 이 뒤로 미룬 판단)</li>
 *   <li>{@link #sessionsInWindow} — 조회 1건이 본 이번 주 세션 수 분포. 위와 같은 목적의
 *       재료를 다른 축(부하가 아니라 «어느 회원이 무거운가»)에서 본다</li>
 *   <li>{@link #ruleFired} — 규칙별 발화 횟수. {@link WeeklySentenceRuleId} 문서 참고 —
 *       코드가 있어도 실사용에서 안 밟히는 분기가 있을 수 있다(#193 이 준 교훈)</li>
 * </ul>
 */
@Component
public class WeeklyReportMetrics {

    private static final String QUERY_LATENCY = "shadowfit.report.weekly.query.latency";
    private static final String SESSIONS_IN_WINDOW = "shadowfit.report.weekly.sessions";
    private static final String RULE_FIRED = "shadowfit.report.weekly.rule.fired";

    // LLM 문장 생성 (report-generation-llm.md §10 «기존 SLO 판정선에 없는 지표들»)
    private static final String LLM_CALL = "shadowfit.report.weekly.llm.call";
    private static final String LLM_LATENCY = "shadowfit.report.weekly.llm.latency";
    private static final String LLM_TOKENS = "shadowfit.report.weekly.llm.tokens";
    private static final String FALLBACK = "shadowfit.report.weekly.fallback";

    private final MeterRegistry registry;

    public WeeklyReportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 조회 1건의 지연(A층+B층 포함, 문장 조립까지). 평균이 아니라 백분위로 본다 — 세션 수가 회원마다 갈려 꼬리가 길 수 있다. */
    public void queryLatency(Duration duration) {
        Timer.builder(QUERY_LATENCY)
                .description("주간 요약 조회 1건의 지연")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(duration);
    }

    /** 조회 1건이 본 이번 주(완료) 세션 수. 캐시/저장이 필요해지는 지점을 찾는 재료다. */
    public void sessionsInWindow(long sessions) {
        DistributionSummary.builder(SESSIONS_IN_WINDOW)
                .description("주간 요약 조회 1건이 본 이번 주 세션 수")
                .register(registry)
                .record(sessions);
    }

    /** @param ruleId 발화한 규칙. tags: rule(규칙 식별자) */
    public void ruleFired(WeeklySentenceRuleId ruleId) {
        registry.counter(RULE_FIRED, "rule", ruleId.name()).increment();
    }

    /** 호출 1건의 결말 — ok / transport-error / rejected / validation:&lt;이유&gt;. 폴백 비율은 fallback 쪽에서 원인별로. */
    public void llmCall(String outcome) {
        registry.counter(LLM_CALL, "outcome", outcome).increment();
    }

    /** 실패 포함 호출 왕복 시간 — 실측(2026-09-14 flash-lite p95 1.66s)이 운영에서도 유지되는지 본다. */
    public void llmLatency(Duration duration) {
        Timer.builder(LLM_LATENCY)
                .description("Gemini generateContent 왕복 시간")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(duration);
    }

    /** 무료 티어 한도는 토큰이 아니라 요청 수지만, 유료 전환 시 비용의 원천이 이 둘이다. null 은 응답에 usage 가 없던 경우. */
    public void llmTokens(Integer promptTokens, Integer outputTokens) {
        if (promptTokens != null) {
            DistributionSummary.builder(LLM_TOKENS).tag("kind", "prompt").register(registry).record(promptTokens);
        }
        if (outputTokens != null) {
            DistributionSummary.builder(LLM_TOKENS).tag("kind", "output").register(registry).record(outputTokens);
        }
    }

    /** 행이 TEMPLATE_FALLBACK 으로 끝난 원인 — no-record / disabled / rejected / validation:&lt;이유&gt; / exhausted. */
    public void fallback(String reason) {
        registry.counter(FALLBACK, "reason", reason).increment();
    }
}
