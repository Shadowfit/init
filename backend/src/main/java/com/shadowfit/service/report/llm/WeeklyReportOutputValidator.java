package com.shadowfit.service.report.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 출력 검증 — 환각 방지의 실무적 형태 (report-generation-llm.md §3 «LLM 이 절대 안 하는 것»).
 * 셋 중 하나라도 어기면 <b>폐기하고 템플릿으로</b> — 재호출하지 않는다(비용을 곱하지 않는다, §5).
 *
 * <ol>
 *   <li>JSON 파싱 + summary 비어 있지 않음</li>
 *   <li><b>숫자 대조</b> — {@code cited_metrics} 의 값과 summary 본문에 나온 숫자 전부가 입력 집계에 있던 수여야 한다.
 *       인용이 0건이면 «달라진 것» 을 숫자 없이 말한 것이라 역시 폐기 — ㄱ 은 숫자 인용이 정의다</li>
 *   <li>한국어만 — 한글·ASCII·기본 부호 밖의 문자(한자·가나·키릴 등)가 있으면 폐기. 영문 단어(rep) 는 용어라 허용</li>
 * </ol>
 *
 * <p>2026-09-14 실측 30회에서 위반 0 이었지만, 그건 «이 검증이 불필요하다» 가 아니라 «지금 모델이 통과한다» 다.
 */
public final class WeeklyReportOutputValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private WeeklyReportOutputValidator() {
    }

    public record Verdict(boolean ok, String reason, String summary, String citedMetricsJson) {
        static Verdict reject(String reason) {
            return new Verdict(false, reason, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(String summary, List<CitedMetric> cited_metrics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CitedMetric(String name, BigDecimal value) {
    }

    public static Verdict validate(String text, Set<BigDecimal> allowedNumbers) {
        Output out;
        try {
            out = MAPPER.readValue(text, Output.class);
        } catch (JsonProcessingException | RuntimeException e) {
            return Verdict.reject("json-parse");
        }
        if (out == null || out.summary() == null || out.summary().isBlank()) {
            return Verdict.reject("empty-summary");
        }
        if (out.cited_metrics() == null || out.cited_metrics().isEmpty()) {
            return Verdict.reject("no-citation");
        }
        for (CitedMetric m : out.cited_metrics()) {
            if (m.value() == null || !allowedNumbers.contains(normalize(m.value()))) {
                return Verdict.reject("cited-unknown-number");
            }
        }
        List<BigDecimal> inText = numbersIn(out.summary());
        for (BigDecimal n : inText) {
            if (!allowedNumbers.contains(n)) {
                return Verdict.reject("text-unknown-number");
            }
        }
        if (!koreanOnly(out.summary())) {
            return Verdict.reject("non-korean");
        }
        try {
            return new Verdict(true, null, out.summary().strip(), MAPPER.writeValueAsString(out.cited_metrics()));
        } catch (JsonProcessingException e) {
            return Verdict.reject("json-parse");
        }
    }

    /** 71.40 과 71.4 를 같은 수로 — 입력 집계·인용·본문 모두 이 정규화를 거쳐 비교한다. */
    static BigDecimal normalize(BigDecimal n) {
        return n.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    static List<BigDecimal> numbersIn(String text) {
        List<BigDecimal> found = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            found.add(normalize(new BigDecimal(m.group())));
        }
        return found;
    }

    /** 허용: ASCII · 한글(음절·자모·호환 자모) · 일반 구두점(U+2000~206F, «» 포함) · 화살표 · 가운뎃점. 그 밖(한자·가나·키릴 …)은 거절. */
    static boolean koreanOnly(String text) {
        return text.chars().allMatch(ch -> ch < 0x80
                || ch == 0x00B7 || ch == 0x00AB || ch == 0x00BB
                || (ch >= 0x2000 && ch <= 0x206F)
                || (ch >= 0x2190 && ch <= 0x21FF)
                || (ch >= 0x1100 && ch <= 0x11FF)
                || (ch >= 0x3130 && ch <= 0x318F)
                || (ch >= 0xAC00 && ch <= 0xD7A3));
    }
}
