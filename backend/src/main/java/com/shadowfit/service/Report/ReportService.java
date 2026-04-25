package com.shadowfit.service.Report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.detailreport.ComparisonWithPreviousDto;
import com.shadowfit.dto.report.detailreport.ExerciseSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.report.Report;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {
    private final ReportRepository reportRepository;
    private final SessionRepository sessionRepository;
    private final PoseDataRepository poseDataRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SessionReportResponseDto getSessionReport(Long sessionId) {
        log.info("세션 리포트 생성 시작 - 세션 ID: {}", sessionId);

        // 1. 기초 세션 정보 및 운동 조회
        Session currentSession = sessionRepository.findSessionWithExerciseById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 2. AI 분석 리포트 엔티티 조회
        Report report = reportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // 3. 이전 동일 운동 세션 조회
        Optional<Session> lastSession = sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                currentSession.getMember().getId(),
                currentSession.getExercise().getId(),
                Status.COMPLETED
        );

        List<PoseData> poseDataList = poseDataRepository.findBySessionIdOrderByTimestampSecAsc(sessionId);
        return buildReportResponse(currentSession, report, lastSession, poseDataList);
    }


    private SessionReportResponseDto buildReportResponse(Session session, Report report,
                                                         Optional<Session> lastSession,
                                                         List<PoseData> poseDataList) {
        SessionReportResponseDto responseDto = SessionReportResponseDto.of(session, report);

        // 1. WorstSection 생성
        if (report.getSummary() != null) {
            WorstSectionDto worst = new WorstSectionDto();
            worst.setExerciseName(report.getSummary());
            worst.setTimeStamp("22:10");
            worst.setReason("싱크로율 저하 및 자세 불균형");
            responseDto.setWorstSection(worst);
        }

        // 2. json 문자열 파싱
        try {
            if (report.getDetailedAnalysis() != null) {
                List<ExerciseSyncRateDto> details = objectMapper.readValue(
                        report.getDetailedAnalysis().toString(),
                        new TypeReference<List<ExerciseSyncRateDto>>() {}
                );
                responseDto.setSyncRateDetails(details);
            }

            if (report.getComparisonWithPrevious() != null) {
                ComparisonWithPreviousDto comparison = objectMapper.readValue(
                        report.getComparisonWithPrevious().toString(),
                        new TypeReference<ComparisonWithPreviousDto>() {}
                );
                responseDto.setComparisonWithPrevious(comparison);
            }
        } catch (Exception e) {
            log.error("리포트 JSON 데이터 파싱 중 에러 발생: {}", e.getMessage());
        }

        // 3. 이전 세션 비교 로직
        lastSession.ifPresent(last -> {
            double diff = session.getAvgSyncRate().doubleValue() - last.getAvgSyncRate().doubleValue();
            log.info("이전 대비 싱크로율 변화: {}", diff);
        });

        return responseDto;
    }


    private int calculateDuration(Session session) {
        if (session.getEndTime() == null) return 0;
        return (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
    }
}