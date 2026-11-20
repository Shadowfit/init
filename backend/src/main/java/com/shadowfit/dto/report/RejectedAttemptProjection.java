package com.shadowfit.dto.report;

import com.shadowfit.model.exercise.RejectedAttemptReason;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 리포트·싱크 통계 계산에 필요한 «미인정 시도» 컬럼만 (V35). {@link PoseFrameProjection} 과 같은 이유로
 * {@code joint_coordinates} 는 싣지 않는다 — 리포트의 worst 구간(3D 프레임)은 인정 반복에서만 고르므로 쓰는 곳이 없다.
 *
 * @param attemptNo    세션 안 미인정 시도 번호(1부터)
 * @param syncRate     인정 반복과 같은 식의 싱크로율 0~100
 * @param minKneeAngle 가장 깊이 앉은 순간의 무릎각. null = 미상
 * @param torsoTiltDeg 그 순간의 체간 기울기(도). null = 미상
 * @param timestampSec {@code pose_data.timestamp_sec} 과 같은 원점의 경과 초
 */
public record RejectedAttemptProjection(
        Integer attemptNo,
        RejectedAttemptReason reason,
        Double syncRate,
        Double minKneeAngle,
        Double torsoTiltDeg,
        Double timestampSec) {

    /**
     * 사유별 개수 — 0 인 사유는 빼고, 사유 코드 선언 순({@link RejectedAttemptReason})으로. 리포트의
     * {@code rejectedRepsByReason} 이 이 모양이다(예: {@code {"DEPTH_NOT_CONFIRMED": 2}}).
     */
    public static Map<String, Integer> countByReason(List<RejectedAttemptProjection> attempts) {
        Map<RejectedAttemptReason, Integer> counts = new EnumMap<>(RejectedAttemptReason.class);
        for (RejectedAttemptProjection attempt : attempts) {
            counts.merge(attempt.reason(), 1, Integer::sum);
        }
        Map<String, Integer> byName = new LinkedHashMap<>();
        counts.forEach((reason, count) -> byName.put(reason.name(), count));
        return byName;
    }
}
