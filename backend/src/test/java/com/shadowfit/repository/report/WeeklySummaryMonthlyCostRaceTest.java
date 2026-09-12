package com.shadowfit.repository.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.Report;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * [weekly-monthly-stat-preaggregation.md §1 후보②] 주간 B층 쿼리({@code JSON_TABLE})를 <b>30일
 * 범위</b>로 넓혔을 때 얼마나 비싸지는지 잰다. 사전집계 착수 여부를 결정하기 전에 "월간으로 넓히면
 * 무거워진다"는 후보②가 실제로 존재하는 문제인지부터 확인하는 측정이다 — 결과는 판단을 내리지
 * 않고 남긴다(해석·결정은 이 클래스가 아니라 위 문서에서).
 *
 * <p><b>헤비유저 가정(2026-09-08 사용자 확정)</b>: 매일 1세션 = 30일 = 30세션(상한 케이스).
 * <b>회차 수 가정</b>: 세션당 30회(={@code mysql/dev-seed.sql} 시연 시드의 "3세트 × 평균 10rep"과
 * 동일 — 근거 없는 새 숫자를 넣지 않는다).
 *
 * <p>실행법은 {@link WeeklySummaryBLayerRaceTest} 와 동일 — {@link MySqlContainerSupport} 가
 * mysql:8.0 컨테이너를 띄우고 Flyway 가 스키마를 만든다. Docker 가 없으면 «건너뜀». 수치는
 * 표준 출력으로만 남기고 단언은 없다 — 측정 코드지 회귀 테스트가 아니다.
 * 09-08 실측(문서 §7)은 이 구조 이전, 수동 3307 컨테이너 + {@code -Drace.mysql=true} 로 돌렸다.
 *
 * <p>🔴 로컬 2코어 박스 실측이라 절대 ms는 무의미할 수 있다([[project_loadtest_env_constraint]]) —
 * 신뢰할 것은 "7일 대비 30일이 몇 배인가"라는 구조적 비율과 EXPLAIN 계획 변화 여부다. 반복 없는
 * 단발 실행은 잡음에 약하므로({@link #REPEATS}) 여러 번 돌려 최소값을 본다
 * ([[feedback_measure_design_needs_repeats]]).
 */
@SpringBootTest
@ActiveProfiles("race")
@DisplayName("주간 B층 쿼리 — 30일 범위로 넓혔을 때 비용 (사전집계 착수 전 측정)")
class WeeklySummaryMonthlyCostRaceTest extends MySqlContainerSupport {

    @Autowired private WeeklySummaryQueryRepository repository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int HEAVY_USER_DAYS = 30;
    private static final int REPS_PER_SESSION = 30;
    private static final int REPEATS = 7;

    /** 시딩·질의 양쪽의 "지금"을 고정 — 실행 시각에 따라 경계가 흔들리지 않게. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 9, 0);
    private static final LocalDateTime WEEK_FROM = NOW.minusDays(7);
    private static final LocalDateTime MONTH_FROM = NOW.minusDays(30);

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("monthly-cost@test.com").username("월간비용측정").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        // V2__seed_master_data.sql 이 이미 심어둔 스쿼트(id=1)를 그대로 쓴다 — 카테고리를 새로
        // 만들면 V10__add_categories_table.sql 의 시드(LOWER 등)와 유니크 제약이 충돌한다.
        exercise = exercisesRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("V2 시드(스쿼트, id=1)가 없다 — 마이그레이션 확인"));

        for (int day = 0; day < HEAVY_USER_DAYS; day++) {
            LocalDateTime startTime = NOW.minusDays(day).withHour(9).withMinute(0).withSecond(0).withNano(0);
            seedWithReport(startTime);
        }
    }

    @AfterEach
    void tearDown() {
        // exercise(id=1, 스쿼트)는 V2 시드 소유라 지우지 않는다 — 회원·세션·리포트만 정리.
        jdbcTemplate.update("DELETE FROM session_reports WHERE member_id = ?", member.getId());
        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id = ?", member.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", member.getId());
    }

    private void seedWithReport(LocalDateTime startTime) {
        List<RepSyncRateDto> repTrend = new ArrayList<>();
        for (int rep = 1; rep <= REPS_PER_SESSION; rep++) {
            repTrend.add(new RepSyncRateDto(rep, 60.0 + (rep % 30), "00:%02d".formatted(rep)));
        }
        WorstSectionDto worst = new WorstSectionDto();
        worst.setRepNumber(REPS_PER_SESSION / 2);
        worst.setExerciseName("스쿼트");
        worst.setTimeStamp("00:%02d".formatted(REPS_PER_SESSION / 2));
        worst.setReason("측정용 시드");

        Session s = sessionRepository.saveAndFlush(Session.builder()
                .member(member)
                .exercise(exercise)
                .startTime(startTime)
                .status(Status.COMPLETED)
                .totalReps(REPS_PER_SESSION)
                .avgSyncRate(new BigDecimal("75.00"))
                .build());

        Report report = new Report();
        report.setMember(member);
        report.setSession(s);
        try {
            report.setDetailedAnalysis(objectMapper.writeValueAsString(new SessionDetailedAnalysis(worst, repTrend)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        reportRepository.saveAndFlush(report);
    }

    private long timeMs(Supplier<?> call) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < REPEATS; i++) {
            long start = System.nanoTime();
            call.get();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            best = Math.min(best, elapsedMs);
        }
        return best;
    }

    @Test
    @DisplayName("7일 vs 30일 범위 — repCurveBetween/worstRepDistributionBetween 최소 응답시간(ms), N=7 최소값")
    void 범위별_비용_비교() {
        long weekCurveMs = timeMs(() -> repository.repCurveBetween(member.getId(), WEEK_FROM, NOW));
        long monthCurveMs = timeMs(() -> repository.repCurveBetween(member.getId(), MONTH_FROM, NOW));
        long weekWorstMs = timeMs(() -> repository.worstRepDistributionBetween(member.getId(), WEEK_FROM, NOW));
        long monthWorstMs = timeMs(() -> repository.worstRepDistributionBetween(member.getId(), MONTH_FROM, NOW));

        System.out.println("=== #weekly-monthly-stat-preaggregation §1 후보② 실측 ===");
        System.out.printf("seed: %d세션 × %d회차 (헤비유저 상한, 2026-09-08 확정)%n", HEAVY_USER_DAYS, REPS_PER_SESSION);
        System.out.printf("repCurveBetween        7일=%dms   30일=%dms   배율=%.2fx%n",
                weekCurveMs, monthCurveMs, ratio(weekCurveMs, monthCurveMs));
        System.out.printf("worstRepDistribution    7일=%dms   30일=%dms   배율=%.2fx%n",
                weekWorstMs, monthWorstMs, ratio(weekWorstMs, monthWorstMs));

        System.out.println("--- EXPLAIN (30일 범위, repCurveBetween과 동일 쿼리 형태) ---");
        jdbcTemplate.queryForList("""
                EXPLAIN SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*)
                  FROM session_reports r
                  JOIN exercise_sessions s ON s.id = r.session_id
                 CROSS JOIN JSON_TABLE(r.detailed_analysis, '$.repTrend[*]'
                        COLUMNS (rep_number INT PATH '$.repNumber',
                                 sync_rate DOUBLE PATH '$.syncRate')) jt
                 WHERE r.member_id = %d
                   AND s.start_time >= '%s' AND s.start_time < '%s'
                 GROUP BY jt.rep_number
                 ORDER BY jt.rep_number
                """.formatted(member.getId(), MONTH_FROM, NOW))
                .forEach(row -> System.out.println(row));
    }

    private double ratio(long weekMs, long monthMs) {
        if (weekMs == 0) return monthMs == 0 ? 1.0 : Double.POSITIVE_INFINITY;
        return monthMs / (double) weekMs;
    }
}
