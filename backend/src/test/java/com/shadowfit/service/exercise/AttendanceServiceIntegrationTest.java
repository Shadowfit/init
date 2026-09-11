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

import java.math.BigDecimal;
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
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
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

    private void sessionOn(Member m, LocalDate date, Status status) {
        sessionRepository.saveAndFlush(Session.builder()
                .member(m).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 20))
                .status(status).totalReps(20)
                .build());
    }
}
