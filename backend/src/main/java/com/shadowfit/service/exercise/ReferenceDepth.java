package com.shadowfit.service.exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.AdminReferenceSummaryDto;
import com.shadowfit.model.exercise.ExerciseReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기준 좌표가 «얼마나 깊은가» — 관리자 화면의 기준 깊이 판정(2026-10-21 결정 B).
 *
 * <p>각도는 <b>AI 와 똑같이</b> 잰다({@code ai-server/app/core/angle_calculator.py calculate_angle}): 이미지 평면 2D,
 * x 에 화면 비율(너비/높이)을 곱해 y 와 같은 길이 단위로 맞춘다. 화면 비율은 프레임 JSON 의 {@code index=-1} 표식
 * 항목의 {@code image_aspect_ratio} 이고 없으면 1.0(보정 없음). 정의가 AI 와 갈리면 «관리자가 본 깊이» 와
 * «채점이 쓰는 깊이» 가 달라져 이 판정이 거짓말이 된다.
 * <ul>
 *   <li>무릎 = 23-25-27(왼쪽)·24-26-28(오른쪽) 의 가운데 관절 각도 평균</li>
 *   <li>고관절 = 11-23-25(왼쪽)·12-24-26(오른쪽) 평균</li>
 *   <li>프레임마다 구한 뒤 전 프레임의 최솟값(= 가장 깊은 순간), 소수 첫째 자리 반올림</li>
 * </ul>
 * 한쪽 랜드마크가 빠진 프레임은 있는 쪽만 쓰고, 양쪽 다 없으면 그 프레임은 건너뛴다.
 */
final class ReferenceDepth {

    /**
     * 깊이 판정 경계(설계값, 2026-10-21). 기준의 가장 깊은 무릎각이
     * <ul>
     *   <li>{@value #STRICT_BELOW_KNEE_DEG}° 미만 — 기준이 너무 깊어 보통 사람의 스쿼트는 대부분 감점된다 → 깐깐(STRICT)</li>
     *   <li>{@value #LENIENT_ABOVE_KNEE_DEG}° 초과 — 기준이 얕아 얕은 스쿼트도 만점을 받는다 → 후함(LENIENT)</li>
     *   <li>그 사이 — 평행 스쿼트(무릎 약 90°) 근처라 적당(OK)</li>
     * </ul>
     * 실측으로 튜닝한 값이 아니다. 숫자를 바꾸면 관리자 화면의 판정 문구만 바뀌고 채점은 그대로다.
     */
    static final double STRICT_BELOW_KNEE_DEG = 60.0;
    static final double LENIENT_ABOVE_KNEE_DEG = 95.0;

    /** AI 의 {@code calculate_angle} 분모에 더하는 값과 같다 — 0 길이 벡터에서 NaN 대신 90° 가 나온다. */
    private static final double EPS = 1e-8;

    private static final int[][] KNEE = {{23, 25, 27}, {24, 26, 28}};
    private static final int[][] HIP = {{11, 23, 25}, {12, 24, 26}};

    record Result(Double minKneeDeg, Double minHipDeg) {
        static final Result NONE = new Result(null, null);
    }

    private ReferenceDepth() {}

    static Result measure(List<ExerciseReference> rows, ObjectMapper objectMapper) {
        if (rows == null || rows.isEmpty()) return Result.NONE;
        double minKnee = Double.MAX_VALUE, minHip = Double.MAX_VALUE;
        for (ExerciseReference r : rows) {
            JsonNode root = readArray(r.getJointCoordinates(), objectMapper);
            if (root == null) continue;
            double aspect = 1.0;
            Map<Integer, double[]> points = new HashMap<>();
            for (JsonNode item : root) {
                JsonNode idx = item.get("index");
                if (idx == null || !idx.isNumber()) continue;
                int index = idx.asInt();
                if (index == -1) {
                    double ar = item.path("image_aspect_ratio").asDouble(0);
                    if (ar > 0) aspect = ar;
                }
                if (index < 0) continue;
                points.put(index, new double[]{item.path("x").asDouble(), item.path("y").asDouble()});
            }
            Double knee = meanAngle(points, KNEE, aspect);
            Double hip = meanAngle(points, HIP, aspect);
            if (knee != null) minKnee = Math.min(minKnee, knee);
            if (hip != null) minHip = Math.min(minHip, hip);
        }
        return new Result(minKnee == Double.MAX_VALUE ? null : round1(minKnee),
                minHip == Double.MAX_VALUE ? null : round1(minHip));
    }

    /** 무릎 최솟값으로 판정한다. 값이 없으면(프레임 없음·랜드마크 없음) null. */
    static String verdict(Double minKneeDeg) {
        if (minKneeDeg == null) return null;
        if (minKneeDeg < STRICT_BELOW_KNEE_DEG) return AdminReferenceSummaryDto.VERDICT_STRICT;
        if (minKneeDeg > LENIENT_ABOVE_KNEE_DEG) return AdminReferenceSummaryDto.VERDICT_LENIENT;
        return AdminReferenceSummaryDto.VERDICT_OK;
    }

    /**
     * 프레임 JSON 을 배열로 읽는다. H2(테스트)의 json 컬럼은 문자열을 JSON 문자열 리터럴로 돌려줄 수 있어
     * 한 번 더 푼다({@code AdminExerciseService.summarizeReference} 와 같은 처리). 깨졌으면 null.
     */
    static JsonNode readArray(String json, ObjectMapper objectMapper) {
        if (json == null) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root != null && root.isTextual()) root = objectMapper.readTree(root.asText());
            return root != null && root.isArray() ? root : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Double meanAngle(Map<Integer, double[]> points, int[][] sides, double aspect) {
        double sum = 0;
        int n = 0;
        for (int[] s : sides) {
            double[] a = points.get(s[0]), b = points.get(s[1]), c = points.get(s[2]);
            if (a == null || b == null || c == null) continue;
            sum += angle(a, b, c, aspect);
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    /** b 를 꼭짓점으로 한 a-b-c 각도(0~180°) — {@code calculate_angle} 를 그대로 옮겼다. */
    static double angle(double[] a, double[] b, double[] c, double aspect) {
        double ar = aspect > 0 ? aspect : 1.0;
        double bax = (a[0] - b[0]) * ar, bay = a[1] - b[1];
        double bcx = (c[0] - b[0]) * ar, bcy = c[1] - b[1];
        double cos = (bax * bcx + bay * bcy) / (Math.hypot(bax, bay) * Math.hypot(bcx, bcy) + EPS);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
