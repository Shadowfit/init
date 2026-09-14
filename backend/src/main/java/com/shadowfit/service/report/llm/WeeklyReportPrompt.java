package com.shadowfit.service.report.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 주간 리포트 프롬프트 v1 (report-generation-llm.md §3 «LLM 이 하는 것 딱 셋» 중 ㄱ·ㄴ — §14-3 C-a).
 *
 * <p><b>입력은 집계 숫자만</b>이다 — 원본 pose·이름·이메일·세션 id 없음. 무료 티어 «입력이 학습에 쓰일 수 있음»(§7 ③)
 * 리스크의 면적이 이만큼이다. 키는 <b>한국어 라벨</b>로 준다 — 2026-09-14 실측에서 영문 필드명({@code totalReps})을
 * 그대로 문장에 쓰는 출력이 나왔다. 템플릿 문장도 같이 준다 — 규칙이 이미 «사실» 로 판정한 것을 LLM 이 다시
 * 계산하지 않게 하려는 것이다.
 *
 * <p>{@link #VERSION} 은 저장 행에 박힌다 — 프롬프트를 고치면 올린다. 같은 입력에 다른 문장이 나오는 이유를
 * 나중에 «프롬프트가 바뀌어서» 와 «모델이 흔들려서» 로 가를 수 있어야 한다(§10).
 */
public final class WeeklyReportPrompt {

    public static final String VERSION = "v1";

    /** 실측(loadtest/results/gemini-latency-2026-09-14)과 같은 값 — 낮을수록 같은 입력에 같은 문장. */
    public static final double TEMPERATURE = 0.2;

    static final String SYSTEM = """
            너는 스쿼트 운동 주간 리포트의 문장을 쓰는 보조자다. 반드시 한국어로만 쓴다.
            입력 JSON 의 숫자만 인용하고, 계산·추정·의학적 조언·없는 사실은 절대 쓰지 않는다.
            할 일 두 가지: (1) 지난주 대비 무엇이 달라졌는지 2문장 이내 (2) 회차별 싱크로율 곡선에서 보이는 반복 패턴 1문장.
            «템플릿 문장» 에 이미 적힌 사실을 그대로 되풀이하지 말고, 그 사실들이 함께 뜻하는 바를 쓴다.
            출력은 JSON 하나: summary(문장들을 이은 한 문단, 3문장 이내), cited_metrics(인용한 숫자를 name→value 로).
            """;

    /** Gemini responseSchema — 출력 형식을 강제한다. 파싱 실패 = 검증 실패. */
    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "summary", Map.of("type", "STRING"),
                    "cited_metrics", Map.of(
                            "type", "ARRAY",
                            "items", Map.of(
                                    "type", "OBJECT",
                                    "properties", Map.of(
                                            "name", Map.of("type", "STRING"),
                                            "value", Map.of("type", "NUMBER")),
                                    "required", List.of("name", "value")))),
            "required", List.of("summary", "cited_metrics"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String userText;
    private final Set<BigDecimal> allowedNumbers;

    private WeeklyReportPrompt(String userText, Set<BigDecimal> allowedNumbers) {
        this.userText = userText;
        this.allowedNumbers = allowedNumbers;
    }

    public static WeeklyReportPrompt from(WeeklySummaryResponseDto summary,
                                          List<RepCurvePointDto> repCurve,
                                          List<WorstRepFrequencyDto> worstDistribution) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("기간", summary.periodStart() + " ~ " + summary.periodEnd().minusDays(1));
        input.put("이번 주", totals(summary.thisWeek()));
        input.put("지난주", totals(summary.lastWeek()));
        List<Map<String, Object>> curve = new ArrayList<>();
        for (RepCurvePointDto p : repCurve) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("회차", p.repNumber());
            row.put("평균 싱크로율", p.avgSyncRate());
            row.put("표본 수", p.sampleCount());
            curve.add(row);
        }
        input.put("회차별 평균 싱크로율(rep 곡선)", curve);
        List<Map<String, Object>> worst = new ArrayList<>();
        for (WorstRepFrequencyDto w : worstDistribution) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("회차", w.repNumber());
            row.put("가장 낮았던 세션 수", w.count());
            worst.add(row);
        }
        input.put("세션마다 가장 낮았던 회차의 분포", worst);
        input.put("템플릿 문장", summary.sentences());

        Set<BigDecimal> numbers = new HashSet<>();
        collectNumbers(input, numbers);
        // 템플릿 문장 속 숫자(규칙이 이미 계산해 «준» 값)도 인용 가능한 수다.
        summary.sentences().forEach(sentence -> numbers.addAll(WeeklyReportOutputValidator.numbersIn(sentence)));
        // 기간은 문자열로 줬지만 «9월 7일부터» 처럼 날짜를 인용할 수 있다 — 그 숫자까지 «입력에 있던 수» 로 친다.
        for (java.time.LocalDate d : List.of(summary.periodStart(), summary.periodEnd().minusDays(1))) {
            for (int v : new int[] {d.getYear(), d.getMonthValue(), d.getDayOfMonth()}) {
                numbers.add(WeeklyReportOutputValidator.normalize(BigDecimal.valueOf(v)));
            }
        }
        try {
            return new WeeklyReportPrompt(MAPPER.writeValueAsString(input), Set.copyOf(numbers));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("프롬프트 입력 직렬화 실패", e);
        }
    }

    private static Map<String, Object> totals(WeeklyTotalsDto t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("완료한 세션 수", t.sessions());
        m.put("총 rep 수", t.totalReps());
        m.put("rep 가중 싱크로율", t.repWeightedSyncRate());
        m.put("운동한 날 수", t.activeDays());
        return m;
    }

    /** 입력에 있는 숫자 전부(소수 3자리로 정규화) — 검증기가 «인용해도 되는 숫자» 로 쓴다. 기간 문자열은 숫자가 아니다. */
    private static void collectNumbers(Object o, Set<BigDecimal> acc) {
        if (o instanceof Map<?, ?> m) {
            m.values().forEach(v -> collectNumbers(v, acc));
        } else if (o instanceof List<?> l) {
            l.forEach(v -> collectNumbers(v, acc));
        } else if (o instanceof Number n && !(o instanceof Boolean)) {
            acc.add(WeeklyReportOutputValidator.normalize(new BigDecimal(n.toString())));
        }
    }

    public String systemInstruction() {
        return SYSTEM;
    }

    public String userText() {
        return userText;
    }

    public Map<String, Object> responseSchema() {
        return RESPONSE_SCHEMA;
    }

    public Set<BigDecimal> allowedNumbers() {
        return allowedNumbers;
    }
}
