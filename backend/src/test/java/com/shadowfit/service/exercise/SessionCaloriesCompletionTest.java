package com.shadowfit.service.exercise;

import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.DailyLog;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.report.DailyLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료 경로의 칼로리 (2026-10-21) — AI 가 보낸 {@code calories_burned} 는 버리고 Spring 이 체중으로 추정한다.
 *
 * <p>지키는 것: ① 체중이 있으면 시작~완료 구간으로 추정해 세션과 일지에 같은 값이 들어간다(AI 의 0.0 무시),
 * ② 체중이 없으면 세션은 null(«측정 안 됨»)·일지는 0 을 더한다(AI 가 무슨 값을 보내도).
 */
@SpringBootTest
@Transactional
@DisplayName("완료 경로 칼로리 추정 테스트")
class SessionCaloriesCompletionTest {

    @Autowired private SessionCompletionTx sessionCompletionTx;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private DailyLogRepository dailyLogRepository;
    // 발행기가 테스트 도중 돌면 검증이 흔들린다 (SessionSyncStatsTest 와 같은 이유·같은 컨텍스트).
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Session startSession(String email, Double weightKg, int minutesAgo) {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email(email).username(email.substring(0, email.indexOf('@'))).password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER)
                .weight(weightKg).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true).build());
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(minutesAgo))
                .status(Status.IN_PROGRESS).build());
    }

    private void complete(Session session, double aiCalories) {
        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId())
                .setTotalReps(5)
                .setCaloriesBurned(aiCalories)
                .build());
        sessionRepository.flush();
    }

    private DailyLog dailyLogOf(Session session) {
        return dailyLogRepository.findByMemberIdAndLogDate(
                session.getMember().getId(), session.getStartTime().toLocalDate()).orElseThrow();
    }

    @Test
    @DisplayName("체중 70kg · 10분 세션 → 약 36.75 kcal 가 세션·일지에 같은 값으로 — AI 의 0.0 은 무시")
    void knownWeight_estimatesFromDuration() {
        Session session = startSession("cal-known@test.com", 70.0, 10);

        complete(session, 0.0);

        Session done = sessionRepository.findById(session.getId()).orElseThrow();
        // 3.0 × 3.5 × 70 ÷ 200 = 3.675 kcal/분 × 10분 = 36.75 — 시작 시각 초 절삭(+최대 1초)·실행 시간만큼 조금 더 나온다
        assertThat(done.getCaloriesBurned()).isBetween(new BigDecimal("36.75"), new BigDecimal("37.50"));
        assertThat(dailyLogOf(done).getTotalCalories()).isEqualByComparingTo(done.getCaloriesBurned());
    }

    @Test
    @DisplayName("체중을 모르면 세션 칼로리는 null, 일지는 0 을 더한다 — AI 가 값을 보내도 쓰지 않는다")
    void unknownWeight_nullCalories_dailyLogAddsZero() {
        Session session = startSession("cal-unknown@test.com", null, 10);

        complete(session, 999.0);

        Session done = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(done.getCaloriesBurned()).isNull();
        DailyLog log = dailyLogOf(done);
        assertThat(log.getTotalCalories()).isEqualByComparingTo("0");
        assertThat(log.getTotalExerciseTime()).isEqualTo(10);
    }
}
