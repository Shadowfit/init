package com.shadowfit.repository.report;

import com.shadowfit.dto.report.record.DailyLogRequestDto;
import com.shadowfit.dto.report.record.Mood;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.report.DailyLogService;
import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DailyLogRepository} 의 네이티브 쿼리 두 개를 실제 MySQL 에서 돌린다.
 * ({@code upsertStats} 는 {@code WeeklySummaryBLayerRaceTest} 가 이미 MySQL 에서 본다.)
 *
 * <p><b>왜 H2 로는 모자란가.</b>
 * <ul>
 *   <li>{@code upsertMemoAndMood} 는 {@code mood} 를 <b>문자열</b>로 넘긴다(네이티브라
 *       {@code @Enumerated} 가 안 걸린다). 받는 쪽은 MySQL {@code ENUM(...)} 컬럼이라, 자바 enum 과
 *       DDL 의 값 목록이 어긋나면 운영에서만 거부된다. H2 는 {@code create-drop} 으로 엔티티에서
 *       스키마를 만들어 이 어긋남을 원리상 못 본다.</li>
 *   <li>{@code recomputeStats} 의 분 계산은 MySQL {@code TIMESTAMPDIFF(MINUTE)} 의 <b>절삭</b>에 기대고,
 *       그 값이 {@code SessionCompletionTx} 의 {@code Duration.toMinutes()} 와 같아야 한다는 것이
 *       주석의 전제다. 그 전제를 MySQL 함수로 확인한다.</li>
 * </ul>
 *
 * <p>{@code @Transactional} 로 롤백한다 — 동시성을 보는 테스트가 아니라 한 연결 안에서 끝난다.
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@Transactional
@DisplayName("daily_logs 네이티브 쿼리 — upsertMemoAndMood·recomputeStats (실 MySQL)")
class DailyLogNativeQueryRaceTest extends MySqlContainerSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Autowired private DailyLogService dailyLogService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Member member;
    private Member other;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("dailylog-race@test.local").username("dailylog-race").password("x")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        other = memberRepository.saveAndFlush(Member.builder()
                .email("dailylog-race-other@test.local").username("dailylog-race-other").password("x")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.saveAndFlush(Category.builder().name("DAILYLOG_RACE").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("dailylog-race-squat").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
    }

    @ParameterizedTest
    @EnumSource(Mood.class)
    @DisplayName("upsertMemoAndMood — 자바 Mood 의 모든 값이 MySQL ENUM 컬럼에 들어간다")
    void everyMoodFitsTheEnumColumn(Mood mood) {
        dailyLogService.saveOrUpdateLog(member.getId(), new DailyLogRequestDto(DAY, "memo", mood));

        assertThat(row().get("mood")).isEqualTo(mood.name());
    }

    @Test
    @DisplayName("upsertMemoAndMood — 두 번째 쓰기가 memo·mood 를 덮고, 누적 통계는 건드리지 않는다")
    void secondWriteOverwritesMemoButKeepsStats() {
        dailyLogService.accumulateStats(member.getId(), DAY, 30, new BigDecimal("120.00"));
        dailyLogService.saveOrUpdateLog(member.getId(), new DailyLogRequestDto(DAY, "first", Mood.GOOD));

        dailyLogService.saveOrUpdateLog(member.getId(), new DailyLogRequestDto(DAY, "second", null));

        Map<String, Object> row = row();
        assertThat(row.get("memo")).isEqualTo("second");
        assertThat(row.get("mood")).as("null 도 «마지막 입력이 이긴다» 의 일부다").isNull();
        assertThat(((Number) row.get("total_exercise_time")).intValue()).isEqualTo(30);
        assertThat((BigDecimal) row.get("total_calories")).isEqualByComparingTo("120.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_logs WHERE member_id = ? AND log_date = ?", Integer.class, member.getId(), DAY))
                .as("uk_member_date — 행은 하나").isEqualTo(1);
    }

    @Test
    @DisplayName("recomputeStats — 그날 COMPLETED 세션만, 세션별 분 절삭 합으로 다시 센다")
    void recomputeCountsOnlyCompletedSessionsOfThatDay() {
        // 세션별 TIMESTAMPDIFF(MINUTE) 는 절삭: 10분59초 → 10, 5분30초 → 5. 합 15.
        completed(member, DAY.atTime(9, 0, 0), DAY.atTime(9, 10, 59), "30.50");
        completed(member, DAY.atTime(23, 50, 0), DAY.atTime(23, 55, 30), "20.25");
        // 세지 않을 것들: 다른 상태 · 다음 날 0시 시작(경계 밖) · 남의 세션
        session(member, Status.FAILED, DAY.atTime(12, 0), DAY.atTime(12, 30), "99.00");
        completed(member, DAY.plusDays(1).atStartOfDay(), DAY.plusDays(1).atTime(0, 20), "99.00");
        completed(other, DAY.atTime(10, 0), DAY.atTime(10, 45), "99.00");
        // 재계산 전 값은 일부러 틀리게 둔다 — 덮어쓰는지 본다.
        dailyLogService.accumulateStats(member.getId(), DAY, 999, new BigDecimal("999.00"));

        dailyLogService.recomputeStats(member.getId(), DAY);

        Map<String, Object> row = row();
        assertThat(((Number) row.get("total_exercise_time")).intValue()).isEqualTo(10 + 5);
        assertThat((BigDecimal) row.get("total_calories")).isEqualByComparingTo("50.75");
    }

    @Test
    @DisplayName("recomputeStats — 남은 COMPLETED 가 없으면 0 으로 덮는다 (NULL 이 아니다)")
    void recomputeWithNoSessionsWritesZero() {
        dailyLogService.accumulateStats(member.getId(), DAY, 40, new BigDecimal("80.00"));

        dailyLogService.recomputeStats(member.getId(), DAY);

        Map<String, Object> row = row();
        assertThat(((Number) row.get("total_exercise_time")).intValue()).isZero();
        assertThat((BigDecimal) row.get("total_calories")).isEqualByComparingTo("0");
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap(
                "SELECT memo, mood, total_exercise_time, total_calories FROM daily_logs WHERE member_id = ? AND log_date = ?",
                member.getId(), DAY);
    }

    private void completed(Member owner, LocalDateTime start, LocalDateTime end, String calories) {
        session(owner, Status.COMPLETED, start, end, calories);
    }

    private void session(Member owner, Status status, LocalDateTime start, LocalDateTime end, String calories) {
        Session saved = sessionRepository.saveAndFlush(Session.builder()
                .member(owner).exercise(exercise).startTime(start).endTime(end)
                .status(status).totalReps(5).difficultyLevel(1)
                .caloriesBurned(new BigDecimal(calories)).build());
        assertThat(saved.getId()).isNotNull();
    }
}
