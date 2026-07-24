package com.shadowfit.service.Exercise;

import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PoseDataCleanupService 통합테스트 — 회원 탈퇴 시 pose_data 참조무결성 대체(B5,
 * pose-data-partition-fk-tradeoff.md). @Async라 실제 스레드풀에서 실행되므로 폴링으로 확인.
 */
@SpringBootTest
@Transactional
@DisplayName("PoseDataCleanupService 테스트")
class PoseDataCleanupServiceTest {

    @Autowired private PoseDataCleanupService cleanupService;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;

    private Session targetSession;
    private Session otherSession;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("cleanup@test.com").username("u").password("dummy").role(UserRole.USER).build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        targetSession = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(5).build());
        otherSession = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(5).build());

        poseDataRepository.saveAndFlush(PoseData.builder()
                .session(targetSession).timestampSec(0.0).jointCoordinates("{}")
                .syncRate(70.0).isCorrect(true).build());
        poseDataRepository.saveAndFlush(PoseData.builder()
                .session(otherSession).timestampSec(0.0).jointCoordinates("{}")
                .syncRate(70.0).isCorrect(true).build());
    }

    @Test
    @DisplayName("대상 세션의 pose_data만 지워지고 다른 세션 것은 보존됨")
    void cleanupBySessionIds_removesOnlyTargetSessions() {
        cleanupService.cleanupBySessionIds(List.of(targetSession.getId()));

        await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(poseDataRepository.findFramesBySessionId(targetSession.getId())).isEmpty()
        );
        assertThat(poseDataRepository.findFramesBySessionId(otherSession.getId())).hasSize(1);
    }

    @Test
    @DisplayName("빈 리스트/null이면 아무 것도 지우지 않고 조용히 반환")
    void cleanupBySessionIds_emptyOrNull_noop() {
        cleanupService.cleanupBySessionIds(List.of());
        cleanupService.cleanupBySessionIds(null);

        assertThat(poseDataRepository.findFramesBySessionId(targetSession.getId())).hasSize(1);
        assertThat(poseDataRepository.findFramesBySessionId(otherSession.getId())).hasSize(1);
    }
}
