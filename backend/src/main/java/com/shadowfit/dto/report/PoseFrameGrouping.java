package com.shadowfit.dto.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code pose_data} 프레임을 rep 번호로 묶는 공용 규칙 — {@code SessionAnalysisCalculator}와
 * {@code SquatDepthAnalysisCalculator}가 똑같이 필요로 해서 뽑았다(2026-09-21).
 *
 * <p>{@code repNumber <= 0}은 "미상"이라 제외한다 — 컬럼이 생기기 전에 저장된 행과, rep_number를
 * 안 보내는 구버전 AI의 행이 여기 해당한다. 섞으면 서로 다른 rep이 하나로 뭉뚱그려진다
 * (자세한 배경은 {@code SessionAnalysisCalculator}의 원래 주석 참고 — 이슈 #74·#75).
 */
public final class PoseFrameGrouping {

    private PoseFrameGrouping() {
    }

    /** rep 번호로 묶는다. 입력이 rep 오름차순이면 반환값의 순회 순서도 rep 오름차순이다. */
    public static Map<Integer, List<PoseFrameProjection>> byRep(List<PoseFrameProjection> poseFrames) {
        Map<Integer, List<PoseFrameProjection>> framesByRep = new LinkedHashMap<>();
        for (PoseFrameProjection frame : poseFrames) {
            Integer repNumber = frame.repNumber();
            if (repNumber == null || repNumber <= 0) {
                continue;
            }
            framesByRep.computeIfAbsent(repNumber, key -> new ArrayList<>()).add(frame);
        }
        return framesByRep;
    }
}
