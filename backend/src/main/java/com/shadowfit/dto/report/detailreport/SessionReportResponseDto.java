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
    // 화면 헤더의 날짜 — 리포트 화면이 세션 id 만 들고 들어와 날짜를 따로 알 길이 없었다.
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private java.time.LocalDateTime startTime;
    private int avgSyncRate;
    // 횟수만 측정한 운동의 0 기본값을 점수로 표시하지 않도록 구분한다.
    private boolean scoreAvailable;
    private int totalReps;
    /**
     * «미인정 시도» 수 (2026-10-21, V35) — 내려갔다 올라왔지만 {@code totalReps} 에 안 들어간 시도. 횟수·세트에는 안 들어가지만
     * 점수에는 들어간다: {@code avgSyncRate} 는 인정 반복과 미인정 시도를 한 표씩 센 평균이고, {@code repTrend}·
     * {@code squatDepthAnalysis.repDepths}·{@code squatTrunkLeanAnalysis.repTrunkLeans} 에 {@code counted=false} 행으로 섞인다.
     * 기록 이전에 저장된 리포트는 0.
     */
    private int rejectedReps;
    /** 사유별 미인정 시도 수 — 0 이 아닌 사유만(예: {@code {"DEPTH_NOT_CONFIRMED": 2}}). 없으면 빈 객체. */
    private java.util.Map<String, Integer> rejectedRepsByReason = java.util.Map.of();
    private int workoutMinutes;
    // kcal, 정수 반올림. 2026-10-21 부터 Spring 이 체중으로 추정한다(CalorieEstimator) — 체중을 몰라 못 잰 세션은
    // 0 이 아니라 null 이다(아래 of 주석). 앱은 null 이면 «체중 필요» 로 보여 준다.
    private Integer caloriesBurned;
    private String aiSafetyReport;

    /**
     * 싱크로율 판정 기준 한 쌍(V34) — 세션 종목의 <b>현재</b> 값. 리포트 화면이 점수·회차 추이를
     * «정석(≥ good) / 교정 필요(≥ warn) / 위험(&lt; warn)» 으로 칠하는 근거다(세션 시작 응답과 같은 의미).
     * 세션별로 박제하지 않으므로 관리자가 기준을 바꾸면 지난 리포트의 색도 새 기준을 따른다.
     */
    private Double syncGoodThreshold;
    private Double syncWarnThreshold;

    private WorstSectionDto worstSection;

    /**
     * 회차별 싱크로율 추이(시간 오름차순). 측정된 rep 이 없으면 빈 리스트. 2026-10-21 부터 미인정 시도가
     * {@code counted=false}·{@code repNumber=null} 점으로 섞인다({@link RepSyncRateDto}) — 옛 리포트는 인정 반복뿐이라
     * rep 오름차순 그대로다.
     *
     * <p>{@code syncRateDetails} 는 이름과 달리 <b>세션당 한 줄</b>이라(운동 종목 단위 요약) 회차
     * 흐름을 볼 수 없었다. {@code worstSection} 도 가장 나빴던 한 회차만 알려준다. 데이터는
     * {@code pose_data} 에 rep 별로 이미 있었고 노출 경로만 없었다.
     */
    private List<RepSyncRateDto> repTrend;

    /**
     * 스쿼트 깊이 정확도 리포트(2026-09-21). 런지 세션·구버전 세션·깊이 데이터가 없는 세션은
     * null — {@code worstSection}과 같은 널 가드 규약이다.
     */
    private SquatDepthAnalysisDto squatDepthAnalysis;

    /**
     * 런지 깊이 정확도 리포트(2026-09-27). 스쿼트 세션·구버전 세션(V30 이전)·좌우 구분 깊이
     * 데이터가 없는 세션은 null — 위와 같은 널 가드 규약이다.
     */
    private LungeDepthAnalysisDto lungeDepthAnalysis;

    /**
     * 스쿼트 체간 기울기 정확도 리포트(2026-09-28). 런지 세션·구버전 세션(V31 이전)·체간 기울기
     * 데이터가 없는 세션은 null — 위와 같은 널 가드 규약이다.
     */
    private TrunkLeanAnalysisDto squatTrunkLeanAnalysis;

    /**
     * 런지 체간 기울기 정확도 리포트(2026-09-28). 스쿼트 세션·구버전 세션·체간 기울기 데이터가
     * 없는 세션은 null — 위와 같은 널 가드 규약이다.
     */
    private TrunkLeanAnalysisDto lungeTrunkLeanAnalysis;

    /**
     * 런지 보폭 정확도 리포트(2026-09-28). 스쿼트 세션(보폭 개념 없음)·구버전 세션(V32 이전)·
     * 보폭 데이터가 없는 세션은 null — 위와 같은 널 가드 규약이다.
     */
    private LungeStepLengthAnalysisDto lungeStepLengthAnalysis;

    private List<ExerciseSyncRateDto> syncRateDetails;

    /**
     * 세트별 요약(V26) — set_no 오름차순. 세트 도입 전 세션은 빈 리스트.
     * {@code syncRateDetails[0].setInfo} 가 한 줄 표기라면 이것은 세트마다 한 행이다.
     */
    private List<SessionSetDto> sets = List.of();

    private ComparisonWithPreviousDto comparisonWithPrevious;

    /**
     * <p><b>null 을 0 으로 접는 이유</b>: 이 DTO 의 수치 필드는 원시형(int)이라 "값 없음"을 표현할
     * 수단이 없다. 그런데 세션 쪽 컬럼은 전부 nullable 이다 — 특히 {@code avgSyncRate} 는
     * <b>측정된 rep 이 없으면 의도적으로 null 로 저장한다</b>(이슈 #75,
     * {@code SessionService.applySyncStats}). 0 을 쓰면 "측정 안 됨"이 "싱크로율 0%"로 둔갑해
     * 월 평균 집계를 끌어내리기 때문이다.
     *
     * <p>집계에서는 그 구분이 반드시 필요하지만 <b>단건 리포트 화면에서는 필요 없다</b> — 측정된
     * rep 이 없는 세션은 어차피 {@code totalReps} 도 0 이라 "0회 / 0%"로 자기모순 없이 읽힌다.
     * 그래서 응답 계약(항상 숫자)은 그대로 두고 여기서만 접는다.
     *
     * <p>접기 전에는 {@code getAvgSyncRate().intValue()} 라 null 인 순간 NPE 로 <b>리포트 조회가
     * 500</b> 이 됐다. 지금까지 안 터진 건 AI 가 늘 0.0 을 보내 실제 null 이 없었기 때문이고,
     * #75 수정으로 null 이 실재하게 되면서 드러났다. {@code totalReps}·{@code caloriesBurned} 도
     * 같은 형태의 잠복 NPE 라 함께 막는다.
     *
     * <p><b>2026-10-21 — {@code caloriesBurned} 는 예외가 됐다.</b> 칼로리를 Spring 이 체중으로 추정하면서
     * «체중을 몰라 못 잼(null)» 이 정상 케이스가 됐고, 그 세션은 rep 이 있어 «0회 / 0 kcal» 처럼 자기모순 없이
     * 읽히지 않는다. 그래서 이 필드만 {@code Integer} 로 바꿔 null 을 그대로 내보낸다.
     */
    public static SessionReportResponseDto of(Session session, Report report) {
        SessionReportResponseDto dto = new SessionReportResponseDto();
        dto.setSessionId(session.getId());
        dto.setStartTime(session.getStartTime());
        dto.setAvgSyncRate(intOrZero(session.getAvgSyncRate()));
        dto.setScoreAvailable(session.getAvgSyncRate() != null);
        dto.setTotalReps(session.getTotalReps() == null ? 0 : session.getTotalReps());
        dto.setWorkoutMinutes((int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes());
        // 칼로리만은 null 을 접지 않는다(2026-10-21) — 체중이 없어 못 잰 세션이 실재하게 됐고, 0 으로 접으면 «소모 칼로리 0»
        // 이라는 거짓 값이 된다. 절삭(intValue)이 아니라 반올림인 이유: caloriesDiff 가 반올림이라 화면의 두 값이 어긋나지 않게.
        dto.setCaloriesBurned(session.getCaloriesBurned() == null ? null
                : session.getCaloriesBurned().setScale(0, java.math.RoundingMode.HALF_UP).intValue());
        dto.setAiSafetyReport(report.getImprovementTips());
        // 호출부(ReportService)가 exercise 를 JOIN FETCH 로 읽어 온다 — open-in-view: false 라 여기서 lazy 를 타면 안 된다.
        if (session.getExercise() != null) {
            dto.setSyncGoodThreshold(doubleOrNull(session.getExercise().getSyncGoodThreshold()));
            dto.setSyncWarnThreshold(doubleOrNull(session.getExercise().getSyncWarnThreshold()));
        }
        return dto;
    }

    private static Double doubleOrNull(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static int intOrZero(java.math.BigDecimal value) {
        return value == null ? 0 : value.intValue();
    }
}
