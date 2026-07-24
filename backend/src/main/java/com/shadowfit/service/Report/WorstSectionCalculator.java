package com.shadowfit.service.Report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.model.exercise.Session;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * worst 구간(싱크로율이 가장 낮은 연속 구간) 계산 — 읽기 경로(ReportService)와 쓰기 경로
 * (SessionService.applyComplete, precompute-on-write)가 공유하는 순수 계산 컴포넌트로 분리.
 * Session·List<PoseFrameProjection>만 받고 다른 서비스를 의존하지 않아, SessionService가 이걸
 * 직접 의존해도 ReportService와의 순환 의존이 생기지 않는다(report-read-path.md §9-1).
 */
@Component
public class WorstSectionCalculator {

    // 연속 WORST_WINDOW_SIZE 개의 PoseData 평균 syncRate 가 가장 낮은 구간을 worst 로 선정.
    // 한 점이 아니라 구간을 보는 이유: 단일 프레임은 노이즈 영향이 커서 일시적 튐을 worst 로 잡을 위험.
    private static final int WORST_WINDOW_SIZE = 3;

    public WorstSectionDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (poseFrames == null || poseFrames.size() < WORST_WINDOW_SIZE) {
            return null;
        }

        int worstStart = 0;
        double worstAverage = Double.MAX_VALUE;
        for (int i = 0; i <= poseFrames.size() - WORST_WINDOW_SIZE; i++) {
            double sum = 0.0;
            for (int j = 0; j < WORST_WINDOW_SIZE; j++) {
                Double rate = poseFrames.get(i + j).syncRate();
                if (rate == null) {
                    sum = Double.MAX_VALUE;
                    break;
                }
                sum += rate;
            }
            double average = sum / WORST_WINDOW_SIZE;
            if (average < worstAverage) {
                worstAverage = average;
                worstStart = i;
            }
        }

        // 구간의 중앙 프레임을 대표 timestamp 로 사용
        PoseFrameProjection representative = poseFrames.get(worstStart + WORST_WINDOW_SIZE / 2);
        WorstSectionDto worst = new WorstSectionDto();
        worst.setExerciseName(session.getExercise().getName());
        worst.setTimeStamp(formatTimestamp(representative.timestampSec()));
        worst.setReason(buildWorstReason(worstAverage, poseFrames, worstStart));
        return worst;
    }

    private String formatTimestamp(Double timestampSec) {
        if (timestampSec == null) return "00:00";
        int totalSeconds = timestampSec.intValue();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private String buildWorstReason(double averageSyncRate, List<PoseFrameProjection> list, int start) {
        int syncPercent = (int) Math.round(averageSyncRate);
        String dominantFeedback = pickDominantFeedback(list, start);
        if (dominantFeedback == null || dominantFeedback.isBlank()) {
            return String.format("싱크로율 %d%%", syncPercent);
        }
        return String.format("싱크로율 %d%% · %s", syncPercent, dominantFeedback);
    }

    // worst 구간 안의 feedback_message 중 가장 자주 등장한 것을 reason 보강에 사용
    private String pickDominantFeedback(List<PoseFrameProjection> list, int start) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (int j = 0; j < WORST_WINDOW_SIZE; j++) {
            String message = list.get(start + j).feedbackMessage();
            if (message == null || message.isBlank()) continue;
            counts.merge(message, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
