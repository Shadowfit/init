package com.shadowfit.service.exercise;

import com.google.protobuf.util.Timestamps;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackEvent;
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
import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * {@link FeedbackLogService#saveBatch} 의 MySQL 쪽 판정을 실제 MySQL 에서 확인한다.
 *
 * <p><b>① FK 위반 → SESSION_NOT_FOUND.</b> 서비스는 «존재 검사 뒤 INSERT 사이에 세션이 사라진»
 * 경우를 FK 위반으로 알아채 {@code SESSION_NOT_FOUND} 로 답한다. 판정은 MySQL 벤더 코드
 * {@code 1452}(ER_NO_REFERENCED_ROW_2) 또는 H2·PG 의 SQLState 로 한다. 기본 테스트는 H2 라
 * <b>SQLState 쪽만</b> 돌았고, MySQL 분기 — 특히 {@code rewriteBatchedStatements=true} 로 배치가
 * 재작성될 때 {@code BatchUpdateException} 의 원인 체인에 1452 가 실제로 실리는가 — 는 검증된 적이
 * 없었다. 여기서 틀리면 운영에서 세션 소멸이 FK 가 아닌 위반으로 분류돼 500 이 된다.
 *
 * <p><b>② 삽입 건수.</b> 반환값은 배치 전후 COUNT 차이다(#219 — 재작성된 배치는 행별 결과를
 * -2 로 준다). 운영과 같은 URL 옵션({@code MySqlContainerSupport}) 위에서 재전송 흡수 건수가 맞는지 본다.
 *
 * <p><b>레이스를 타이밍에 맡기지 않는다</b>({@code SignupUsernameRaceTest} 와 같은 방식). 존재 검사
 * ({@code findById})를 스파이로 잡아 <b>없는 세션 id 에도 세션을 돌려주게</b> 만든다 — 검사를 통과한
 * 직후 세션이 지워진 상태와 DB 가 보는 것이 정확히 같다.
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@Transactional
@DisplayName("피드백 배치 — MySQL 1452 번역·중복 흡수 건수 (실 MySQL)")
class FeedbackLogFkRaceTest extends MySqlContainerSupport {

    /** 존재하지 않는 세션 id — AUTO_INCREMENT 가 여기까지 올 일은 없다. */
    private static final long GHOST_SESSION_ID = Long.MAX_VALUE - 7;

    @Autowired private FeedbackLogService feedbackLogService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private SessionRepository sessionRepository;

    private Session session;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("feedback-fk-race@test.local").username("feedback-fk-race").password("x")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.saveAndFlush(Category.builder().name("FEEDBACK_FK_RACE").build());
        // 멘트 템플릿이 없는 종목 — 유형 지원 검사가 «제한 없음» 으로 통과한다(검증 대상이 아니다).
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("feedback-fk-race").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now().minusMinutes(5))
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
    }

    @Test
    @DisplayName("검사 뒤 세션이 사라졌으면 MySQL 1452 가 SESSION_NOT_FOUND 로 번역된다")
    void vanishedSession_isTranslatedToSessionNotFound() {
        // 검사는 «있다» 고 답하지만 DB 에는 그 id 의 세션이 없다 — FK 가 INSERT 를 거부한다.
        doReturn(Optional.of(session)).when(sessionRepository).findById(GHOST_SESSION_ID);

        assertThatThrownBy(() -> feedbackLogService.saveBatch(batch(GHOST_SESSION_ID, 1, 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("재작성 배치에서도 삽입 건수가 맞다 — 재전송은 0 으로 센다")
    void insertedCount_isCorrectUnderRewrittenBatch() {
        FeedbackBatchRequest request = batch(session.getId(), 1, 2);

        int first = feedbackLogService.saveBatch(request);
        int resent = feedbackLogService.saveBatch(request);

        assertThat(first).isEqualTo(2);
        assertThat(resent).as("같은 배치 재전송은 uk_session_rep 가 흡수한다").isZero();
        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("재전송에 새 이벤트가 섞여도 새 것만 센다")
    void insertedCount_resendMixedWithNew() {
        feedbackLogService.saveBatch(batch(session.getId(), 1, 2));

        int second = feedbackLogService.saveBatch(batch(session.getId(), 1, 2, 3));

        assertThat(second).isEqualTo(1);
        assertThat(countRows()).isEqualTo(3);
    }

    // ⚠️ «한 배치 안에» 같은 (rep, 유형)이 두 번 있는 경우는 여기서 고정하지 않는다 — 실 MySQL 에서
    //    재작성된 multi-row INSERT 가 에러 1869(Auto-increment value in UPDATE conflicts…)로 통째로
    //    실패한다(2026-09-24 이 테스트를 쓰다 발견, #816). 지금 AI 는 배치를 항상 1건으로 보내
    //    (ai-server pose.py flush_pending_feedback) 운영 경로에서는 나지 않는다.

    private int countRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_feedback_logs WHERE session_id = ?", Integer.class, session.getId());
    }

    private static FeedbackBatchRequest batch(long sessionId, int... reps) {
        FeedbackBatchRequest.Builder builder = FeedbackBatchRequest.newBuilder()
                .setSessionId(sessionId).setSetNo(1).setIsFinal(false);
        for (int rep : reps) {
            builder.addEvents(FeedbackEvent.newBuilder()
                    .setFeedbackType("KNEE_OUT")
                    .setRepNumber(rep)
                    .setSyncRateAtTrigger(55.0)
                    .setOccurredAt(Timestamps.fromMillis(System.currentTimeMillis())));
        }
        return builder.build();
    }
}
