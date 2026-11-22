package com.shadowfit.service.exercise;

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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 쿼리로 출석 정의를 고정한다 — COMPLETED 만, start_time 기준일, 다른 회원 무관.
 */
@SpringBootTest
@Transactional
@DisplayName("AttendanceService 통합 테스트")
class AttendanceServiceIntegrationTest {

    @Autowired private AttendanceService attendanceService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;

    private Member member;
    private Member other;
    private Exercise exercise;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("att@test.com").username("att").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        other = memberRepository.saveAndFlush(Member.builder()
                .email("other@test.com").username("other").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        var category = categoryRepository.save(com.shadowfit.model.exercise.Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true).build());
    }

    @Test
    @DisplayName("attendedOn — 그날 COMPLETED 가 있어야 true. FAILED·IN_PROGRESS 만 있으면 false, 남의 세션은 무관")
    void attendedOn_completedOnly() {
        sessionOn(member, today, Status.FAILED);
        sessionOn(other, today, Status.COMPLETED);
        assertThat(attendanceService.attendedOn(member.getId(), today)).isFalse();

        sessionOn(member, today, Status.COMPLETED);
        assertThat(attendanceService.attendedOn(member.getId(), today)).isTrue();
        assertThat(attendanceService.attendedOn(member.getId(), today.minusDays(1))).isFalse();
    }

    @Test
    @DisplayName("currentStreak — 오늘·어제 COMPLETED, 그제는 CANCELLED 만 → 2 (취소는 출석이 아니다)")
    void currentStreak_ignoresNonCompleted() {
        sessionOn(member, today, Status.COMPLETED);
        sessionOn(member, today.minusDays(1), Status.COMPLETED);
        sessionOn(member, today.minusDays(2), Status.CANCELLED);
        sessionOn(member, today.minusDays(3), Status.COMPLETED);
        sessionOn(other, today.minusDays(2), Status.COMPLETED); // 남의 출석이 내 구멍을 메우지 않는다

        assertThat(attendanceService.currentStreak(member.getId(), today)).isEqualTo(2);
    }

    @Test
    @DisplayName("currentStreak — FETCH_BATCH 를 넘는 연속도 정확히 센다 (페이지 경계가 실제 쿼리에서도 이어진다)")
    void currentStreak_acrossPageBoundary() {
        int days = AttendanceService.FETCH_BATCH + 5;
        for (int i = 0; i < days; i++) {
            sessionOn(member, today.minusDays(i), Status.COMPLETED);
        }
        sessionOn(member, today.minusDays(days + 1), Status.COMPLETED); // days 일 전이 비어 있음

        assertThat(attendanceService.currentStreak(member.getId(), today)).isEqualTo(days);
    }

    @Test
    @DisplayName("#761 — 미래 세션이 첫 페이지 칸을 차지해도 페이지 경계를 넘는 streak 는 같다 (첫 페이지는 상한 없는 쿼리)")
    void currentStreak_futureSessionsOnFirstPage() {
        int days = AttendanceService.FETCH_BATCH + 5;
        for (int i = 0; i < days; i++) {
            sessionOn(member, today.minusDays(i), Status.COMPLETED);
        }
        sessionOn(member, today.minusDays(days + 1), Status.COMPLETED); // days 일 전이 비어 있음
        for (int i = 1; i <= 3; i++) {
            sessionOn(member, today.plusDays(i), Status.COMPLETED);     // 미래 — 세면 안 된다
        }

        AttendanceService.StreakRun run = attendanceService.currentStreakRun(member.getId(), today);
        assertThat(run.length()).isEqualTo(days);
        assertThat(run.end()).isEqualTo(today);
    }

    @Test
    @DisplayName("#761 — 첫 페이지가 전부 미래 세션으로 차도 그 아래의 streak 를 이어 센다")
    void currentStreak_firstPageAllFuture() {
        for (int i = 1; i <= AttendanceService.FETCH_BATCH; i++) {
            sessionOn(member, today.plusDays(i), Status.COMPLETED);
        }
        sessionOn(member, today.minusDays(1), Status.COMPLETED);
        sessionOn(member, today.minusDays(2), Status.COMPLETED);
        sessionOn(member, today.minusDays(4), Status.COMPLETED);

        AttendanceService.StreakRun run = attendanceService.currentStreakRun(member.getId(), today);
        assertThat(run.length()).isEqualTo(2);
        assertThat(run.end()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("longestStreakRun — COMPLETED 만, 같은 날 여러 세션은 하루, 남의 출석 무관. 오래된 5일 구간이 최근 2일보다 길다")
    void longestStreak_realQuery() {
        for (int i = 60; i >= 56; i--) {
            sessionOn(member, today.minusDays(i), Status.COMPLETED);
        }
        sessionOn(member, today.minusDays(58), Status.COMPLETED); // 같은 날 두 번째 세션
        sessionOn(member, today.minusDays(55), Status.FAILED);    // 실패는 구간을 안 잇는다
        sessionOn(member, today.minusDays(1), Status.COMPLETED);
        sessionOn(member, today, Status.COMPLETED);
        sessionOn(other, today.minusDays(55), Status.COMPLETED);   // 남의 출석이 내 구간을 안 잇는다

        AttendanceService.StreakRun run = attendanceService.longestStreakRun(member.getId(), today);
        assertThat(run.length()).isEqualTo(5);
        assertThat(run.start()).isEqualTo(today.minusDays(60));
        assertThat(run.end()).isEqualTo(today.minusDays(56));
    }

    @Test
    @DisplayName("attendedDays — 양 끝 포함, COMPLETED 만, 기간 밖은 제외")
    void attendedDays_inclusiveRange() {
        sessionOn(member, today.minusDays(7), Status.COMPLETED); // 기간 밖
        sessionOn(member, today.minusDays(6), Status.COMPLETED); // from
        sessionOn(member, today.minusDays(3), Status.CANCELLED);
        sessionOn(member, today, Status.COMPLETED);              // to

        assertThat(attendanceService.attendedDays(member.getId(), today.minusDays(6), today))
                .containsExactlyInAnyOrder(today.minusDays(6), today);
    }

    // ─── 측정된 것이 없는 세션은 출석이 아니다 (사용자 결정 2026-11-22) ───────────────────────────────────────

    @Test
    @DisplayName("attendedOn — 0회·점수 없는 COMPLETED 만 있는 날은 출석이 아니다. 같은 날 측정된 세션이 하나라도 있으면 출석")
    void attendedOn_unmeasuredIsNotAttendance() {
        unmeasuredOn(member, today);
        assertThat(attendanceService.attendedOn(member.getId(), today)).isFalse();

        sessionOn(member, today, Status.COMPLETED); // 같은 날 측정된 세션
        assertThat(attendanceService.attendedOn(member.getId(), today)).isTrue();
    }

    @Test
    @DisplayName("미인정 시도만 있어 점수가 있는 세션(인정 0회)은 측정된 것이라 출석이다 — 카메라 가림과 다르다")
    void attendedOn_attemptsOnlyCounts() {
        sessionRepository.saveAndFlush(Session.builder().member(member).exercise(exercise)
                .startTime(today.atTime(10, 0)).endTime(today.atTime(10, 10))
                .status(Status.COMPLETED).totalReps(0).avgSyncRate(new java.math.BigDecimal("42.50")).build());

        assertThat(attendanceService.attendedOn(member.getId(), today)).isTrue();
    }

    @Test
    @DisplayName("횟수만 재는 종목(횟수는 있고 점수 없음)은 측정된 것이라 출석이다 — 런지가 출석에서 빠지면 안 된다")
    void attendedOn_countOnlyExerciseCounts() {
        sessionRepository.saveAndFlush(Session.builder().member(member).exercise(exercise)
                .startTime(today.atTime(10, 0)).endTime(today.atTime(10, 10))
                .status(Status.COMPLETED).totalReps(8).avgSyncRate(null).build());

        assertThat(attendanceService.attendedOn(member.getId(), today)).isTrue();
    }

    @Test
    @DisplayName("currentStreak — 중간의 «측정 안 됨» 날은 구멍이다 / 오늘이 측정 안 됨이면 어제부터 센다")
    void currentStreak_unmeasuredBreaksTheRun() {
        sessionOn(member, today, Status.COMPLETED);
        unmeasuredOn(member, today.minusDays(1));      // 구멍
        sessionOn(member, today.minusDays(2), Status.COMPLETED);
        assertThat(attendanceService.currentStreak(member.getId(), today)).isEqualTo(1);

        // 오늘만 측정 안 됨 — «오늘 안 했으면 어제부터» 규칙이 그대로 적용돼 어제 이어서 센다
        Member another = memberRepository.saveAndFlush(Member.builder()
                .email("att2@test.com").username("att2").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        unmeasuredOn(another, today);
        sessionOn(another, today.minusDays(1), Status.COMPLETED);
        sessionOn(another, today.minusDays(2), Status.COMPLETED);
        assertThat(attendanceService.currentStreak(another.getId(), today)).isEqualTo(2);
    }

    @Test
    @DisplayName("currentStreak — 최근 세션이 전부 측정 안 됨(페이지 크기 이상)이어도 그 뒤 기록이 오늘·어제 앵커가 아니면 0")
    void currentStreak_manyUnmeasuredRowsDoNotPollutePages() {
        for (int i = 0; i < AttendanceService.FETCH_BATCH + 9; i++) {
            unmeasuredOn(member, today.minusDays(i));
        }
        assertThat(attendanceService.currentStreak(member.getId(), today)).isZero();
    }

    @Test
    @DisplayName("longestStreakRun · attendedDays — 측정 안 된 날은 구간을 잇지 않고 출석일 집합에도 없다")
    void longestAndAttendedDays_excludeUnmeasured() {
        sessionOn(member, today.minusDays(6), Status.COMPLETED);
        unmeasuredOn(member, today.minusDays(5));       // 구멍
        sessionOn(member, today.minusDays(4), Status.COMPLETED);
        sessionOn(member, today.minusDays(3), Status.COMPLETED);

        AttendanceService.StreakRun run = attendanceService.longestStreakRun(member.getId(), today);
        assertThat(run.length()).isEqualTo(2);
        assertThat(run.start()).isEqualTo(today.minusDays(4));
        assertThat(attendanceService.attendedDays(member.getId(), today.minusDays(6), today))
                .containsExactlyInAnyOrder(today.minusDays(6), today.minusDays(4), today.minusDays(3));
    }

    @Test
    @DisplayName("여러 회원 — attendedOn(집합)·attendeeCountsByDay 도 측정 안 된 세션을 세지 않는다")
    void groupQueries_excludeUnmeasured() {
        unmeasuredOn(member, today);                   // 안 됨
        sessionOn(other, today, Status.COMPLETED);     // 됨

        assertThat(attendanceService.attendedOn(java.util.List.of(member.getId(), other.getId()), today))
                .containsExactly(other.getId());
        assertThat(attendanceService.attendeeCountsByDay(java.util.List.of(member.getId(), other.getId()), today, today))
                .containsEntry(today, 1);
    }

    /**
     * 규칙은 한 곳이 아니라 셋(엔티티 {@code isMeasured}·JPQL·목록 DTO)에 있다 — 같은 입력에서 JPQL 쿼리와 자바 메서드가 같은 답을
     * 내는지 모든 조합으로 본다. 한쪽만 고치면 이 시험이 깨진다.
     */
    @Test
    @DisplayName("Session.isMeasured() 와 출석 JPQL 조건이 모든 입력 조합에서 일치한다")
    void javaRuleAndJpqlRuleAgree() {
        Integer[] reps = {null, 0, 5};
        java.math.BigDecimal[] avgs = {null, new java.math.BigDecimal("42.50")};
        int n = 0;
        for (Integer r : reps) {
            for (java.math.BigDecimal a : avgs) {
                n++;
                Member m = memberRepository.saveAndFlush(Member.builder()
                        .email("rule-" + n + "@test.com").username("rule" + n)
                        .password("dummy").selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
                Session s = sessionRepository.saveAndFlush(Session.builder().member(m).exercise(exercise)
                        .startTime(today.atTime(10, 0)).endTime(today.atTime(10, 5))
                        .status(Status.COMPLETED).totalReps(r).avgSyncRate(a).build());
                Session saved = sessionRepository.findById(s.getId()).orElseThrow();
                boolean viaJpql = !sessionRepository.findMemberIdsWithStatusBetween(
                        java.util.List.of(m.getId()), Status.COMPLETED, today.atStartOfDay(), today.atTime(23, 59, 59)).isEmpty();
                assertThat(viaJpql).as("reps=%s avg=%s (저장값 reps=%s)", r, a, saved.getTotalReps())
                        .isEqualTo(saved.isMeasured());
            }
        }
    }

    private void unmeasuredOn(Member m, LocalDate date) {
        sessionRepository.saveAndFlush(Session.builder()
                .member(m).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 5))
                .status(Status.COMPLETED).totalReps(0).avgSyncRate(null)
                .build());
    }

    private void sessionOn(Member m, LocalDate date, Status status) {
        sessionRepository.saveAndFlush(Session.builder()
                .member(m).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 20))
                .status(status).totalReps(20)
                .build());
    }
}
