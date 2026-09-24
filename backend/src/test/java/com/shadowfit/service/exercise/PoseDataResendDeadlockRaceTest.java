package com.shadowfit.service.exercise;

import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재전송이 원본과 겹칠 때의 데드락 (#276) — {@code savePoseDataBatch} 를 RC 로 돌린 처방의 회귀 가드.
 *
 * <p><b>무엇을 고정하나.</b> 기본 RR 에서는 중복 키 한 건이 {@code PRIMARY} 의 파티션 끝(supremum)에
 * X 락을 잡고, 서로 다른 세션의 재전송이 동시에 겹치면 그 X 를 둘이 쥔 채 서로의 insert intention 을
 * 기다려 데드락이 된다(결정적 재현: {@code loadtest/results/r276-lock-trace-2026-09-24/}). 같은 모양을
 * 서비스 경로로 건다 — 세션 {@value #SESSIONS} 개가 각자 같은 배치를 {@value #RESENDS} 번(첫 번만 신규,
 * 나머지는 전부 중복) 동시에 보낸다.
 *
 * <p><b>왜 «0» 을 단언할 수 있나.</b> 같은 모양의 SQL 판에서 RR 은 문장당 약 46%, RC 는 0/960 이었다.
 * 이 테스트는 {@value #SESSIONS} × {@value #RESENDS} 번을 부르므로 RR 로 되돌리면 사실상 반드시 걸린다
 * (2026-09-24, 격리수준을 빼고 돌려 실패하는 것을 확인했다). 데드락 재시도는 {@code ExerciseGrpcService}
 * 에 있고 이 테스트는 서비스를 직접 부르므로, 여기서 잡히는 것은 재시도로 가려지기 전의 데드락이다.
 *
 * <p>H2 는 이 잠금을 재현하지 못한다 — 실 MySQL({@link MySqlContainerSupport})에서만 돈다.
 */
@SpringBootTest
@ActiveProfiles("race")
@DisplayName("pose 재전송 데드락 (#276)")
class PoseDataResendDeadlockRaceTest extends MySqlContainerSupport {

    private static final int SESSIONS = 8;
    private static final int RESENDS = 20;
    private static final int FRAME_COUNT = 50;   // DOWNSAMPLE_WINDOW=5 → 10행

    @Autowired private PoseDataService poseDataService;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long memberId;
    private final List<Long> sessionIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : sessionIds) {
            jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE id = ?", id);
        }
        if (memberId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
        }
    }

    @Test
    @DisplayName("서로 다른 세션의 중복 재전송이 동시에 겹쳐도 데드락이 나지 않고, 멱등은 유지된다")
    void concurrentDuplicateResendsDoNotDeadlock() throws Exception {
        seedSessions();
        List<PoseDataRequest> batch = frames();

        ExecutorService pool = Executors.newFixedThreadPool(SESSIONS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger deadlocks = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> others = new ConcurrentLinkedQueue<>();

        for (Long sessionId : sessionIds) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < RESENDS; i++) {
                    try {
                        poseDataService.savePoseDataBatch(sessionId, batch);
                    } catch (PessimisticLockingFailureException e) {   // 데드락·락 대기 초과의 공통 조상
                        deadlocks.incrementAndGet();
                    } catch (Throwable t) {
                        others.add(t);
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("시간 안에 끝나야 한다").isTrue();

        assertThat(others).as("데드락 외 예외는 없어야 한다").isEmpty();
        assertThat(deadlocks.get())
                .as("RR 로 되돌리면 중복 한 건이 파티션 끝을 잠가 여기서 걸린다 — %d/%d",
                        deadlocks.get(), SESSIONS * RESENDS)
                .isZero();

        for (Long sessionId : sessionIds) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, sessionId);
            assertThat(rows).as("세션 %d: 재전송 %d번이 한 벌로 접혀야 한다", sessionId, RESENDS)
                    .isEqualTo(FRAME_COUNT / 5);
        }
    }

    private void seedSessions() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("resend-deadlock@test.com").username("재전송데드락").password("dummy")
                .role(UserRole.USER).build());
        memberId = member.getId();

        Category category = categoryRepository.findByName("LOWER")
                .orElseGet(() -> categoryRepository.save(Category.builder().name("LOWER").build()));
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        // 시작 시각을 같게 둔다 — created_at 이 세션 시작 시각이라 전부 같은 파티션(같은 달)에 들어간다.
        // 동시에 운동 중인 사용자는 정의상 같은 달이므로 이것이 실사용 조건이다.
        LocalDateTime startTime = LocalDateTime.now().withNano(0);
        for (int i = 0; i < SESSIONS; i++) {
            Session session = sessionRepository.saveAndFlush(Session.builder()
                    .member(member).exercise(exercise).startTime(startTime)
                    .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
            sessionIds.add(session.getId());
        }
    }

    private List<PoseDataRequest> frames() {
        List<PoseDataRequest> frames = new ArrayList<>();
        for (int i = 0; i < FRAME_COUNT; i++) {
            frames.add(PoseDataRequest.newBuilder()
                    .setTimestampSec(i * 0.1)
                    .setJointCoordinates("{}")
                    .setSyncRate(72.5)
                    .setRepNumber(1)
                    .setSmoothedKneeAngle(120.0)
                    .setFeedbackMessage("ok")
                    .build());
        }
        return frames;
    }
}
