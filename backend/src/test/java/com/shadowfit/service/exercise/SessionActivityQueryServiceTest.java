package com.shadowfit.service.exercise;

import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code SessionService} 의 조회 집계 3개(getWeeklyActivity/getCalendarMain/getDailyActivity)를
 * {@link SessionActivityQueryService} 로 분리하면서(#176) {@code SessionServiceTest.Aggregation}
 * 에서 그대로 옮겨왔다 — 순수 이동이라 케이스 내용은 바뀌지 않았다.
 */
@SpringBootTest
@Transactional
@DisplayName("SessionActivityQueryService 테스트")
class SessionActivityQueryServiceTest {

    @Autowired private SessionActivityQueryService sessionActivityQueryService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("activityquery@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true)
                .build());
    }

    private Session completedSessionOn(LocalDate date, double avgSyncRate, double calories, int minutes) {
        LocalDateTime start = date.atTime(9, 0);
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(minutes))
                .status(Status.COMPLETED).totalReps(10)
                .avgSyncRate(BigDecimal.valueOf(avgSyncRate))
                .caloriesBurned(BigDecimal.valueOf(calories))
                .build());
    }

    private Session sessionOn(LocalDate date, Status status) {
        LocalDateTime start = date.atTime(9, 0);
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(10))
                .status(status).totalReps(10)
                .build());
    }

    @Test
    @DisplayName("getWeeklyActivity — 이번 주 세션 합산")
    void getWeeklyActivity_aggregatesThisWeek() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);

        WeeklyActivityResponseDto result = sessionActivityQueryService.getWeeklyActivity(member.getId());

        assertThat(result.getTotalWorkouts()).isEqualTo(1);
        assertThat(result.getTotalMinutes()).isEqualTo(20);
        assertThat(result.getTotalCalories()).isEqualTo(100);
        assertThat(result.getTodayDetails()).hasSize(1);
    }

    @Test
    @DisplayName("getWeeklyActivity — 칼로리 null(체중 몰라 못 잰 세션, 2026-10-21) 은 합계에서 0 으로 친다")
    void getWeeklyActivity_nullCaloriesCountAsZero() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);
        sessionOn(today, Status.COMPLETED); // caloriesBurned == null

        WeeklyActivityResponseDto result = sessionActivityQueryService.getWeeklyActivity(member.getId());

        assertThat(result.getTotalWorkouts()).isEqualTo(2);
        assertThat(result.getTotalCalories()).isEqualTo(100);
    }

    @Test
    @DisplayName("getCalendarMain — 이번 달 운동일수·평균 싱크로율 계산")
    void getCalendarMain_aggregatesThisMonth() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        assertThat(result.getMonthlyExerciseDays()).isEqualTo(1);
        assertThat(result.getTotalAvgSyncRate()).isEqualTo(80);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).isHasRecord()).isTrue();
        assertThat(result.getConsecutiveDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("getCalendarMain — 연속일수는 COMPLETED 세션만 센다 (출석 정의 통일, " +
            "social-cheer-and-group-feed.md §3-B 2026-09-11 — 예전엔 FAILED·IN_PROGRESS 도 세서 3이 나왔다)")
    void getCalendarMain_consecutiveDaysCountsCompletedOnly() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);
        sessionOn(today.minusDays(1), Status.FAILED);
        sessionOn(today.minusDays(2), Status.IN_PROGRESS);

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        assertThat(result.getConsecutiveDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("getCalendarMain — avg_sync_rate가 null인 세션은 0점이 아니라 평균에서 제외된다")
    void getCalendarMain_nullSyncRateExcludedFromAverage() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);
        // 분석 전/실패라 값이 없는 세션 — 0으로 치면 월평균이 40으로 반토막 난다
        LocalDateTime start = today.atTime(11, 0);
        sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(10))
                .status(Status.COMPLETED).totalReps(5)
                .avgSyncRate(null)
                .caloriesBurned(BigDecimal.valueOf(50))
                .build());

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        assertThat(result.getTotalAvgSyncRate()).isEqualTo(80);
        assertThat(result.getRecords().get(0).getDailyAvgSyncRate()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("getCalendarMain — 모든 세션의 sync_rate가 null이면 평균 0으로 떨어진다")
    void getCalendarMain_allNullSyncRate_fallsBackToZero() {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atTime(11, 0);
        sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(10))
                .status(Status.COMPLETED).totalReps(5)
                .avgSyncRate(null).caloriesBurned(BigDecimal.valueOf(50))
                .build());

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        // 평균 낼 값이 하나도 없을 때의 fallback — 운동일수는 그대로 1일로 잡혀야 한다
        assertThat(result.getTotalAvgSyncRate()).isZero();
        assertThat(result.getMonthlyExerciseDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("getCalendarMain — month 가 1~12 밖이면 DateTimeException(500)이 아니라 INVALID_INPUT_VALUE(400) (#733)")
    void getCalendarMain_monthOutOfRange_isInvalidInput() {
        for (int month : new int[]{0, 13}) {
            assertThatThrownBy(() -> sessionActivityQueryService.getCalendarMain(member.getId(), 2026, month))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    @Test
    @DisplayName("getDailyActivity — 특정 날짜의 세션만 반환, 빈 날은 빈 리스트")
    void getDailyActivity_returnsOnlyThatDate() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);

        DailyActivityResponseDto todayResult = sessionActivityQueryService.getDailyActivity(member.getId(), today);
        DailyActivityResponseDto yesterdayResult = sessionActivityQueryService.getDailyActivity(member.getId(), today.minusDays(1));

        assertThat(todayResult.getTotalWorkouts()).isEqualTo(1);
        assertThat(yesterdayResult.getTotalWorkouts()).isZero();
        assertThat(yesterdayResult.getSessions()).isEmpty();
    }

    /** rep·점수 값을 직접 정해 완료 세션을 만든다 — null 은 «측정 안 됨» 이다. */
    private Session completedWith(LocalDate date, Integer totalReps, Double avgSyncRate) {
        LocalDateTime start = date.atTime(9, 0);
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(5))
                .status(Status.COMPLETED).totalReps(totalReps)
                .avgSyncRate(avgSyncRate == null ? null : BigDecimal.valueOf(avgSyncRate))
                .build());
    }

    @Test
    @DisplayName("measured — 0회이고 점수도 없는 세션(사람이 안 잡힘)은 false")
    void measured_zeroRepsNoScore_isFalse() {
        LocalDate today = LocalDate.now();
        Session s = completedWith(today, 0, null);

        var dto = sessionActivityQueryService.getDailyActivity(member.getId(), today).getSessions().get(0);

        assertThat(dto.getSessionId()).isEqualTo(s.getId());
        assertThat(dto.isMeasured()).isFalse();
        assertThat(dto.isScoreAvailable()).isFalse();
    }

    @Test
    @DisplayName("measured — totalReps 가 null 인 구세션도 점수가 없으면 false (NPE 없이)")
    void measured_nullRepsNoScore_isFalse() {
        LocalDate today = LocalDate.now();
        completedWith(today, null, null);

        var dto = sessionActivityQueryService.getDailyActivity(member.getId(), today).getSessions().get(0);

        assertThat(dto.isMeasured()).isFalse();
    }

    @Test
    @DisplayName("measured — 횟수만 재는 종목(횟수는 있고 점수 없음)은 true — «측정 안 됨» 으로 오표시하지 않는다")
    void measured_countOnlyWithReps_isTrue() {
        LocalDate today = LocalDate.now();
        completedWith(today, 8, null);

        var dto = sessionActivityQueryService.getDailyActivity(member.getId(), today).getSessions().get(0);

        assertThat(dto.isMeasured()).isTrue();
        assertThat(dto.isScoreAvailable()).isFalse();
    }

    @Test
    @DisplayName("measured — 인정 반복은 0이어도 점수가 있으면(미인정 시도만 있었다) true")
    void measured_attemptsOnlyWithScore_isTrue() {
        LocalDate today = LocalDate.now();
        completedWith(today, 0, 42.5);

        var dto = sessionActivityQueryService.getDailyActivity(member.getId(), today).getSessions().get(0);

        assertThat(dto.isMeasured()).isTrue();
        assertThat(dto.isScoreAvailable()).isTrue();
    }

    @Test
    @DisplayName("measured — 정상 세션은 true, 주간 요약(todayDetails)에도 같은 값이 실린다")
    void measured_normalSession_isTrue_andAlsoInWeekly() {
        LocalDate today = LocalDate.now();
        completedWith(today, 10, 80.0);
        completedWith(today, 0, null);

        var weekly = sessionActivityQueryService.getWeeklyActivity(member.getId());

        assertThat(weekly.getTodayDetails()).extracting(d -> d.isMeasured()).containsExactlyInAnyOrder(true, false);
    }
}
