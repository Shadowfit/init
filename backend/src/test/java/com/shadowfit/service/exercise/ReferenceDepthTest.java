package com.shadowfit.service.exercise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.model.exercise.ExerciseReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준 깊이 계산이 AI({@code angle_calculator.py calculate_angle})와 같은 정의인지 — 2D, x 에 화면 비율을 곱한다.
 */
@DisplayName("기준 깊이(ReferenceDepth) 테스트")
class ReferenceDepthTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ExerciseReference row(String json) {
        return ExerciseReference.builder().timestampSec(0.0).jointCoordinates(json).build();
    }

    /** 왼쪽 무릎만 — 엉덩이 (0.5,0.4) · 무릎 (0.5,0.6) · 발목 (0.6,0.6): 비율 1.0 이면 90°. */
    private static String leftKnee(String marker) {
        return "[{\"index\":23,\"x\":0.5,\"y\":0.4},{\"index\":25,\"x\":0.5,\"y\":0.6},"
                + "{\"index\":27,\"x\":0.6,\"y\":0.6}" + marker + "]";
    }

    @Test
    @DisplayName("화면 비율이 없으면 1.0 — 직각은 90°, 한쪽 다리만 있어도 그쪽으로 잰다")
    void noAspect_rightAngle() {
        ReferenceDepth.Result r = ReferenceDepth.measure(List.of(row(leftKnee(""))), objectMapper);

        assertThat(r.minKneeDeg()).isEqualTo(90.0);
        assertThat(r.minHipDeg()).isNull(); // 어깨가 없다
        assertThat(ReferenceDepth.verdict(r.minKneeDeg())).isEqualTo("OK");
    }

    @Test
    @DisplayName("index=-1 의 image_aspect_ratio 를 x 에 곱한다 — 0.5 면 같은 좌표가 다른 각도가 된다")
    void aspectRatio_scalesX() {
        // 엉덩이를 대각선 위로 옮겨 x 성분이 각도에 들어가게 한다: 엉덩이 (0.4,0.5), 무릎 (0.5,0.6), 발목 (0.6,0.6)
        String json = "[{\"index\":23,\"x\":0.4,\"y\":0.5},{\"index\":25,\"x\":0.5,\"y\":0.6},"
                + "{\"index\":27,\"x\":0.6,\"y\":0.6},{\"index\":-1,\"x\":0,\"y\":0,\"image_aspect_ratio\":0.5}]";
        // ba = (-0.1*0.5, -0.1) = (-0.05,-0.1), bc = (0.05, 0) → acos(-0.05·0.05 / (|ba|·0.05)) = 116.6°
        double expected = Math.toDegrees(Math.acos(-0.05 / Math.hypot(0.05, 0.1)));

        ReferenceDepth.Result r = ReferenceDepth.measure(List.of(row(json)), objectMapper);

        assertThat(r.minKneeDeg()).isEqualTo(Math.round(expected * 10) / 10.0);
        assertThat(r.minKneeDeg()).isEqualTo(116.6);
        assertThat(ReferenceDepth.verdict(r.minKneeDeg())).isEqualTo("LENIENT");
    }

    @Test
    @DisplayName("JSON 문자열 리터럴로 감싸 온 값(H2)도 풀어서 읽고, 깨진 프레임은 건너뛴다")
    void textualJson_andBrokenFrames() throws Exception {
        String wrapped = objectMapper.writeValueAsString(leftKnee(""));

        ReferenceDepth.Result r = ReferenceDepth.measure(List.of(row("not json"), row(wrapped)), objectMapper);

        assertThat(r.minKneeDeg()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("판정 경계 — 60 미만 STRICT, 60~95 OK, 95 초과 LENIENT, 값 없음 null")
    void verdictBoundaries() {
        assertThat(ReferenceDepth.verdict(59.9)).isEqualTo("STRICT");
        assertThat(ReferenceDepth.verdict(60.0)).isEqualTo("OK");
        assertThat(ReferenceDepth.verdict(95.0)).isEqualTo("OK");
        assertThat(ReferenceDepth.verdict(95.1)).isEqualTo("LENIENT");
        assertThat(ReferenceDepth.verdict(null)).isNull();
        assertThat(ReferenceDepth.measure(List.of(), objectMapper)).isEqualTo(ReferenceDepth.Result.NONE);
    }
}
