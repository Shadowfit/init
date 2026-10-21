package com.shadowfit.service.exercise;

import com.shadowfit.dto.report.detailreport.RepDepthDto;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.RejectedAttemptRequest;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.report.ReportService;
import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «미인정 시도»(V35)를 <b>실 MySQL</b> 위에서 — H2 가 원리상 못 보는 것들: Flyway 로 만든 표의 UNIQUE·FK CASCADE·JSON 컬럼,
 * 그 위의 {@code ON DUPLICATE KEY UPDATE} 멱등(동시 재전송 포함), 그리고 시드 스쿼트(V2·V4, 종목 id 1)로 도는
 * 완료 집계 → precompute JSON(JSON 컬럼 왕복) → 리포트 한 사이클.
 *
 * <p>{@code race} 프로파일이라 롤백이 없다 — 남긴 것은 {@code @AfterEach} 가 지운다(회원을 지우면 세션·리포트·세트·미인정
 * 시도·일지가 FK CASCADE 로 따라가고, {@code pose_data} 만 FK 가 없어 직접 지운다).
 */
@SpringBootTest
@ActiveProfiles("race")
@DisplayName("미인정 시도 (실 MySQL, V35)")
class RejectedAttemptRaceTest extends MySqlContainerSupport {

    /** V2 시드 스쿼트 — V4 가 기준 좌표를 넣어 깊이 목표가 실제 기준에서 유도된다. */
    private static final long SEEDED_SQUAT_ID = 1L;

    @Autowired private RejectedAttemptService rejectedAttemptService;
    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionCompletionTx sessionCompletionTx;
    @Autowired private ReportService reportService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Member member;
    private Session session;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("rejected-race@test.com").username("미인정레이스").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Exercise squat = exercisesRepository.findById(SEEDED_SQUAT_ID).orElseThrow();
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(squat)
                .startTime(LocalDateTime.now().minusMinutes(5))
                .status(Status.IN_PROGRESS).build());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", session.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", member.getId());
    }

    private RejectedAttemptRequest.Builder attempt(int attemptNumber) {
        return RejectedAttemptRequest.newBuilder()
                .setSessionId(session.getId()).setAttemptNumber(attemptNumber)
                .setReason("DEPTH_NOT_CONFIRMED").setSyncRate(42.0)
                .setMinKneeAngle(104.5).setTorsoTiltDeg(31.0).setTimestampSec(6.0)
                .setJointCoordinates("[{\"index\":25,\"x\":0.5,\"y\":0.5,\"z\":0.0,\"visibility\":1.0}]");
    }

    private int rowCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exercise_rejected_attempts WHERE session_id = ?", Integer.class, session.getId());
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("스키마 — (session_id, attempt_no) UNIQUE, exercise_sessions 로의 FK 는 ON DELETE CASCADE")
    void schema() {
        List<String> uniqueColumns = jdbcTemplate.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exercise_rejected_attempts'
                   AND INDEX_NAME = 'uk_rejected_attempt' AND NON_UNIQUE = 0
                 ORDER BY SEQ_IN_INDEX
                """, String.class);
        assertThat(uniqueColumns).containsExactly("session_id", "attempt_no");

        String deleteRule = jdbcTemplate.queryForObject("""
                SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                 WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'exercise_rejected_attempts'
                   AND REFERENCED_TABLE_NAME = 'exercise_sessions'
                """, String.class);
        assertThat(deleteRule).isEqualTo("CASCADE");
    }

    @Test
    @DisplayName("재전송은 행을 늘리지 않는다 — JSON 컬럼에 좌표가 유효한 JSON 으로, 무릎각 0 은 NULL 로")
    void idempotentInsert_jsonColumn() {
        assertThat(rejectedAttemptService.save(attempt(1).build())).isTrue();
        assertThat(rejectedAttemptService.save(attempt(1).build())).isFalse();
        assertThat(rejectedAttemptService.save(attempt(2).setMinKneeAngle(0.0).build())).isTrue();

        assertThat(rowCount()).isEqualTo(2);
        Map<String, Object> first = jdbcTemplate.queryForMap(
                "SELECT JSON_VALID(joint_coordinates) AS valid, JSON_LENGTH(joint_coordinates) AS len, min_knee_angle "
                        + "FROM exercise_rejected_attempts WHERE session_id = ? AND attempt_no = 1", session.getId());
        assertThat(((Number) first.get("valid")).intValue()).isEqualTo(1);
        assertThat(((Number) first.get("len")).intValue()).isEqualTo(1);
        assertThat(((Number) first.get("min_knee_angle")).doubleValue()).isEqualTo(104.5);
        Object secondKnee = jdbcTemplate.queryForObject(
                "SELECT min_knee_angle FROM exercise_rejected_attempts WHERE session_id = ? AND attempt_no = 2",
                Object.class, session.getId());
        assertThat(secondKnee).isNull();
    }

    @Test
    @DisplayName("같은 시도가 동시에 두 번 와도 한 행이고 아무도 실패하지 않는다 (ODKU 가 경합을 흡수)")
    void concurrentRetransmissions_singleRow() throws Exception {
        // 둘 다 «아직 없다» 를 보고(잠금 없는 읽기) INSERT 로 들어가는 경합 — 뒤엣것은 UNIQUE 에서 기다렸다가 no-op 이 된다.
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return rejectedAttemptService.save(attempt(1).build());
                }));
            }
            for (Future<Boolean> result : results) {
                result.get(30, TimeUnit.SECONDS); // 예외 없이 끝나야 한다
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("세션 행을 지우면 미인정 시도도 FK CASCADE 로 지워진다")
    void sessionDelete_cascades() {
        rejectedAttemptService.save(attempt(1).build());
        assertThat(rowCount()).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE id = ?", session.getId());

        assertThat(rowCount()).isZero();
    }

    /** rep 하나 = AI 배치 하나(프레임 5장 → 다운샘플 후 가장 깊은 1행). */
    private void sendRep(int repNumber, double syncRate, double startSec, double bottomKnee) {
        List<PoseDataRequest> frames = new ArrayList<>();
        double[] knees = {160.0, 120.0, bottomKnee, 120.0, 160.0};
        for (int i = 0; i < knees.length; i++) {
            frames.add(PoseDataRequest.newBuilder()
                    .setRepNumber(repNumber).setTimestampSec(startSec + i * 0.3)
                    .setJointCoordinates("[{\"index\":25,\"x\":0.5,\"y\":0.5}]")
                    .setSyncRate(syncRate).setSmoothedKneeAngle(knees[i]).setTorsoTiltDeg(30.0)
                    .build());
        }
        poseDataService.savePoseDataBatch(session.getId(), frames);
    }

    @Test
    @DisplayName("완료 → precompute JSON(JSON 컬럼) → 리포트 — 평균은 모든 시도, 횟수는 인정 반복, 깊이에 counted=false 행")
    void completionAndReport_endToEnd() {
        sendRep(1, 82.0, 1.0, 80.0);
        rejectedAttemptService.save(attempt(1).setSyncRate(42.0).setTimestampSec(4.0).build());
        sendRep(2, 88.0, 7.0, 78.0);

        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId()).setTotalReps(2).build());

        Map<String, Object> stats = jdbcTemplate.queryForMap(
                "SELECT total_reps, avg_sync_rate, max_sync_rate, min_sync_rate FROM exercise_sessions WHERE id = ?",
                session.getId());
        assertThat(((Number) stats.get("total_reps")).intValue()).isEqualTo(2);
        assertThat(((Number) stats.get("avg_sync_rate")).doubleValue()).isEqualTo(70.67); // (82+42+88)/3
        assertThat(((Number) stats.get("min_sync_rate")).doubleValue()).isEqualTo(42.0);
        assertThat(((Number) stats.get("max_sync_rate")).doubleValue()).isEqualTo(88.0);

        SessionReportResponseDto report = reportService.getSessionReport(session.getId(), member.getId());

        assertThat(report.getTotalReps()).isEqualTo(2);
        assertThat(report.getAvgSyncRate()).isEqualTo(70);
        assertThat(report.getRejectedReps()).isEqualTo(1);
        assertThat(report.getRejectedRepsByReason()).containsExactly(Map.entry("DEPTH_NOT_CONFIRMED", 1));
        assertThat(report.getRepTrend()).extracting(RepSyncRateDto::getRepNumber).containsExactly(1, null, 2);
        assertThat(report.getRepTrend().get(1).isCounted()).isFalse();
        assertThat(report.getRepTrend().get(1).getTimestampSec()).isEqualTo(4.0);
        assertThat(report.getWorstSection().getRepNumber()).isEqualTo(1);
        // 시드 스쿼트라 깊이 분석이 돈다 — 미인정 시도가 같은 공식으로 끼고 코멘트 끝에 미인정 문장
        assertThat(report.getSquatDepthAnalysis()).isNotNull();
        assertThat(report.getSquatDepthAnalysis().getRepDepths())
                .extracting(RepDepthDto::isCounted).containsExactly(true, false, true);
        assertThat(report.getSquatDepthAnalysis().getNarrative()).last().asString()
                .startsWith("1회는 바닥 자세가 확인되지 않아 인정되지 않았어요");
        assertThat(report.getSquatTrunkLeanAnalysis().getRepTrunkLeans()).hasSize(3);
    }
}
