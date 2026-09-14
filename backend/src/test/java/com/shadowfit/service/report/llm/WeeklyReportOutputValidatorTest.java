package com.shadowfit.service.report.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("주간 리포트 LLM 출력 검증 — 없는 숫자·빈 인용·한국어 아님은 폐기")
class WeeklyReportOutputValidatorTest {

    private static final Set<BigDecimal> ALLOWED = Stream.of("3", "4", "41", "58", "74.8", "71.4", "3.4")
            .map(BigDecimal::new).map(WeeklyReportOutputValidator::normalize).collect(Collectors.toSet());

    @Test
    @DisplayName("입력에 있는 숫자만 인용한 한국어 JSON 은 통과하고 summary·cited_metrics 를 돌려준다")
    void valid() {
        String text = """
                {"summary":"지난주 41회에서 58회로 늘었지만 싱크로율은 74.8에서 71.4로 내려갔어요. 4회차 이후 떨어지는 패턴이에요.",
                 "cited_metrics":[{"name":"총 rep 수","value":58},{"name":"rep 가중 싱크로율","value":71.40}]}
                """;
        var v = WeeklyReportOutputValidator.validate(text, ALLOWED);
        assertThat(v.ok()).isTrue();
        assertThat(v.summary()).startsWith("지난주 41회");
        assertThat(v.citedMetricsJson()).contains("\"value\":58").contains("71.40");
    }

    @Test
    @DisplayName("cited_metrics 에 입력에 없는 수가 있으면 cited-unknown-number")
    void citedUnknown() {
        String text = "{\"summary\":\"싱크로율이 내려갔어요.\",\"cited_metrics\":[{\"name\":\"x\",\"value\":99.9}]}";
        assertThat(WeeklyReportOutputValidator.validate(text, ALLOWED).reason()).isEqualTo("cited-unknown-number");
    }

    @Test
    @DisplayName("본문에 입력에 없는 숫자(계산한 백분율 등)가 있으면 text-unknown-number — 인용 목록이 멀쩡해도")
    void textUnknown() {
        String text = "{\"summary\":\"rep 가 41.5% 늘었어요.\",\"cited_metrics\":[{\"name\":\"총 rep 수\",\"value\":58}]}";
        assertThat(WeeklyReportOutputValidator.validate(text, ALLOWED).reason()).isEqualTo("text-unknown-number");
    }

    @Test
    @DisplayName("인용이 0건이면 no-citation — «달라진 것» 을 숫자 없이 말한 것")
    void noCitation() {
        String text = "{\"summary\":\"조금 늘었어요.\",\"cited_metrics\":[]}";
        assertThat(WeeklyReportOutputValidator.validate(text, ALLOWED).reason()).isEqualTo("no-citation");
    }

    @Test
    @DisplayName("한자·가나가 섞이면 non-korean, 영문 용어(rep)와 % 는 허용")
    void nonKorean() {
        String jp = "{\"summary\":\"58回になりました\",\"cited_metrics\":[{\"name\":\"a\",\"value\":58}]}";
        assertThat(WeeklyReportOutputValidator.validate(jp, ALLOWED).reason()).isEqualTo("non-korean");
        String ok = "{\"summary\":\"rep 58회, 싱크로율 71.4% 예요.\",\"cited_metrics\":[{\"name\":\"a\",\"value\":58}]}";
        assertThat(WeeklyReportOutputValidator.validate(ok, ALLOWED).ok()).isTrue();
    }

    @Test
    @DisplayName("한글이 한 자도 없는 영문 문장은 non-korean — ASCII 만으로 통과하면 «한국어만» 이 아니다")
    void englishOnly() {
        String en = "{\"summary\":\"Reps went from 41 to 58.\",\"cited_metrics\":[{\"name\":\"a\",\"value\":58}]}";
        assertThat(WeeklyReportOutputValidator.validate(en, ALLOWED).reason()).isEqualTo("non-korean");
    }

    @Test
    @DisplayName("JSON 이 아니거나 summary 가 비면 json-parse / empty-summary")
    void malformed() {
        assertThat(WeeklyReportOutputValidator.validate("이건 JSON 이 아니다", ALLOWED).reason()).isEqualTo("json-parse");
        assertThat(WeeklyReportOutputValidator.validate("{\"summary\":\"  \",\"cited_metrics\":[]}", ALLOWED).reason())
                .isEqualTo("empty-summary");
    }

    @Test
    @DisplayName("정규화 — 71.40 과 71.4, 58 과 58.000 은 같은 수")
    void normalize() {
        assertThat(WeeklyReportOutputValidator.normalize(new BigDecimal("71.40")))
                .isEqualTo(WeeklyReportOutputValidator.normalize(new BigDecimal("71.4")));
        assertThat(WeeklyReportOutputValidator.normalize(new BigDecimal("58.000")))
                .isEqualTo(WeeklyReportOutputValidator.normalize(new BigDecimal("58")));
    }
}
