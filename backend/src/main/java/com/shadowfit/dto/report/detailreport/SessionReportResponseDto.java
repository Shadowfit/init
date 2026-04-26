package com.shadowfit.dto.report.detailreport;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.report.Report;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionReportResponseDto {
    private Long sessionId;
    private int avgSyncRate;
    private int totalReps;
    private int workoutMinutes;
    private int caloriesBurned;
    private String aiSafetyReport;

    private WorstSectionDto worstSection;
    private List<ExerciseSyncRateDto> syncRateDetails;

    private ComparisonWithPreviousDto comparisonWithPrevious;

    public static SessionReportResponseDto of(Session session, Report report) {
        SessionReportResponseDto dto = new SessionReportResponseDto();
        dto.setSessionId(session.getId());
        dto.setAvgSyncRate(session.getAvgSyncRate().intValue());
        dto.setTotalReps(session.getTotalReps());
        dto.setWorkoutMinutes((int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes());
        dto.setCaloriesBurned(session.getCaloriesBurned().intValue());
        dto.setAiSafetyReport(report.getImprovementTips());
        return dto;
    }
}
