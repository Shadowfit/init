package com.shadowfit.service.exercise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.grpc.RejectedAttemptRequest;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionSet;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.exercise.SessionSetRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.service.report.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 세션 완료·리포트에서의 «미인정 시도» (V35, 2026-10-21) — 수신(RPC 서비스)부터 완료 집계·precompute·리포트 조회까지
 * 한 사이클을 H2 위에서 돈다.
 *
 * <p>지키는 것: ① 세션 avg/max/min 싱크로율은 인정 반복과 미인정 시도를 한 표씩 센다(«평균 싱크로율 = 모든 시도»),
 * ② {@code total_reps}·세트는 인정 반복만, ③ 리포트에 미인정 수·사유별 수가 실리고 회차 추이에 시간순으로 섞인다,
 * ④ 미인정 시도가 없는 세션은 예전과 같은 값이다. 스쿼트 전용 깊이·체간 계산은 종목 id 가 1 이어야 돌아서
 * 계산기 단위 테스트({@code SquatDepthAnalysisCalculatorTest} 등)가 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("미인정 시도 — 완료 집계·리포트 (V35)")
class RejectedAttemptCompletionTest {

    @Autowired private SessionCompletionTx sessionCompletionTx;
    @Autowired private RejectedAttemptService rejectedAttemptService;
    @Autowired private ReportService reportService;
    @Autowired private SessionService sessionService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private SessionSetRepository sessionSetRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    // 발행기가 테스트 도중 돌면 검증이 흔들린다 (SessionSyncStatsTest 와 같은 이유·같은 컨텍스트).
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Member member;
    private Session session;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("rejected-complete@test.com").username("rejectedcomplete").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true).build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(10))
                .targetRepsPerSet(2)
                .status(Status.IN_PROGRESS).build());
    }

    /** 인정 반복 하나 — 프레임 둘, 같은 싱크로율, {@code startSec} 부터 1초 간격. */
    private void saveRep(int repNumber, double syncRate, double startSec) {
        for (int i = 0; i < 2; i++) {
            poseDataRepository.save(PoseData.builder()
                    .session(session).repNumber(repNumber)
                    .timestampSec(startSec + i).jointCoordinates("{}").syncRate(syncRate)
                    .build());
        }
        poseDataRepository.flush();
        // created_at 을 세션 시작 시각으로 맞춘다 (#392 앵커 등호 조회의 전제 — SessionSyncStatsTest 와 같다).
        jdbcTemplate.update("UPDATE pose_data SET created_at = ? WHERE session_id = ?",
                session.getStartTime(), session.getId());
    }

    private void saveRejected(int attemptNumber, String reason, double syncRate, double timestampSec) {
        rejectedAttemptService.save(RejectedAttemptRequest.newBuilder()
                .setSessionId(session.getId()).setAttemptNumber(attemptNumber).setReason(reason)
                .setSyncRate(syncRate).setMinKneeAngle(105.0).setTorsoTiltDeg(30.0)
                .setTimestampSec(timestampSec).setJointCoordinates("[]")
                .build());
    }

    private void complete(int aiTotalReps) {
        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId())
                .setTotalReps(aiTotalReps)
                .build());
        sessionRepository.flush();
    }

    private Session reload() {
        return sessionRepository.findById(session.getId()).orElseThrow();
    }

    @Test
    @DisplayName("평균 싱크로율 = 모든 시도 — 인정 80·90 + 미인정 40 → 평균 70, 최대 90, 최소 40. 횟수·세트는 인정 반복만")
    void syncStatsIncludeRejected_repsAndSetsDoNot() {
        saveRep(1, 80.0, 10.0);
        saveRejected(1, "DEPTH_NOT_CONFIRMED", 40.0, 20.0);
        saveRep(2, 90.0, 30.0);

        complete(2);

        Session done = reload();
        assertThat(done.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(done.getTotalReps()).isEqualTo(2);
        assertThat(done.getAvgSyncRate()).isEqualByComparingTo("70.00");
        assertThat(done.getMaxSyncRate()).isEqualByComparingTo("90.00");
        assertThat(done.getMinSyncRate()).isEqualByComparingTo("40.00");

        // 세트는 인정 반복만 — 목표 2회짜리 한 세트, 평균 (80+90)/2
        List<SessionSet> sets = sessionSetRepository.findBySessionIdOrderBySetNo(session.getId());
        assertThat(sets).hasSize(1);
        assertThat(sets.get(0).getReps()).isEqualTo(2);
        assertThat(sets.get(0).getAvgSyncRate()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("미인정 시도가 없으면 예전과 같다 — 인정 80·90 → 평균 85, 최소 80")
    void withoutRejected_unchanged() {
        saveRep(1, 80.0, 10.0);
        saveRep(2, 90.0, 30.0);

        complete(2);

        Session done = reload();
        assertThat(done.getAvgSyncRate()).isEqualByComparingTo("85.00");
        assertThat(done.getMaxSyncRate()).isEqualByComparingTo("90.00");
        assertThat(done.getMinSyncRate()).isEqualByComparingTo("80.00");

        SessionReportResponseDto report = reportService.getSessionReport(session.getId(), member.getId());
        assertThat(report.getRejectedReps()).isZero();
        assertThat(report.getRejectedRepsByReason()).isEmpty();
        assertThat(report.getRepTrend())
                .extracting(RepSyncRateDto::getRepNumber, RepSyncRateDto::isCounted, RepSyncRateDto::getAttemptNumber)
                .containsExactly(tuple(1, true, null), tuple(2, true, null));
    }

    @Test
    @DisplayName("인정 0회·미인정만 있는 세션도 점수가 남는다 — «측정 안 됨» 이 아니라 시도들의 평균")
    void onlyRejected_scoreFromAttempts() {
        saveRejected(1, "DEPTH_NOT_CONFIRMED", 40.0, 5.0);
        saveRejected(2, "SPLIT_STANCE", 50.0, 9.0);

        complete(0);

        Session done = reload();
        assertThat(done.getTotalReps()).isZero();
        assertThat(done.getAvgSyncRate()).isEqualByComparingTo("45.00");
        assertThat(done.getMaxSyncRate()).isEqualByComparingTo("50.00");
        assertThat(done.getMinSyncRate()).isEqualByComparingTo("40.00");

        SessionReportResponseDto report = reportService.getSessionReport(session.getId(), member.getId());
        assertThat(report.isScoreAvailable()).isTrue();
        assertThat(report.getTotalReps()).isZero();
        assertThat(report.getRejectedReps()).isEqualTo(2);
        assertThat(report.getRejectedRepsByReason())
                .containsExactly(Map.entry("DEPTH_NOT_CONFIRMED", 1), Map.entry("SPLIT_STANCE", 1));
        assertThat(report.getWorstSection()).as("worst 는 인정 반복에서만 고른다").isNull();
        assertThat(report.getRepTrend()).extracting(RepSyncRateDto::getAttemptNumber).containsExactly(1, 2);
    }

    @Test
    @DisplayName("리포트 — 미인정 수·사유별 수가 실리고, 회차 추이에 시간순으로 섞이며, worst·평균은 규칙대로")
    void report_carriesRejectedAttempts() throws Exception {
        saveRep(1, 80.0, 10.0);
        saveRejected(1, "DEPTH_NOT_CONFIRMED", 40.0, 20.0);
        saveRep(2, 90.0, 30.0);
        saveRejected(2, "DEPTH_NOT_CONFIRMED", 55.0, 41.0);

        complete(2);

        // precompute 된 JSON 에 새 필드가 박제된다
        String stored = reportRepository.findBySessionId(session.getId()).orElseThrow().getDetailedAnalysis();
        SessionDetailedAnalysis analysis = new ObjectMapper().readValue(stored, SessionDetailedAnalysis.class);
        assertThat(analysis.getRejectedReps()).isEqualTo(2);
        assertThat(analysis.getRejectedRepsByReason()).containsExactly(Map.entry("DEPTH_NOT_CONFIRMED", 2));

        SessionReportResponseDto report = reportService.getSessionReport(session.getId(), member.getId());

        assertThat(report.getTotalReps()).isEqualTo(2);
        assertThat(report.getAvgSyncRate()).isEqualTo(66); // (80+40+90+55)/4 = 66.25 → int
        assertThat(report.getRejectedReps()).isEqualTo(2);
        assertThat(report.getRejectedRepsByReason()).containsExactly(Map.entry("DEPTH_NOT_CONFIRMED", 2));
        assertThat(report.getRepTrend())
                .extracting(RepSyncRateDto::getRepNumber, RepSyncRateDto::getAttemptNumber,
                        RepSyncRateDto::isCounted, RepSyncRateDto::getSyncRate, RepSyncRateDto::getRejectReason)
                .containsExactly(
                        tuple(1, null, true, 80.0, null),
                        tuple(null, 1, false, 40.0, "DEPTH_NOT_CONFIRMED"),
                        tuple(2, null, true, 90.0, null),
                        tuple(null, 2, false, 55.0, "DEPTH_NOT_CONFIRMED"));
        RepSyncRateDto firstRejected = report.getRepTrend().get(1);
        assertThat(firstRejected.getTimeStamp()).isEqualTo("00:20");
        assertThat(firstRejected.getTimestampSec()).isEqualTo(20.0);
        assertThat(report.getRepTrend().get(0).getTimestampSec()).isNotNull();
        // worst 는 인정 반복에서만 — 미인정 40 이 가장 낮아도 rep1(80)이 worst 다
        assertThat(report.getWorstSection().getRepNumber()).isEqualTo(1);
        // 세트는 인정 반복만
        assertThat(report.getSets()).hasSize(1);
        assertThat(report.getSets().get(0).reps()).isEqualTo(2);
        assertThat(report.getSets().get(0).avgSyncRate()).isEqualTo(85.0);
    }

    @Test
    @DisplayName("세션을 지우면 미인정 시도도 같이 지워진다 (FK ON DELETE CASCADE)")
    void sessionDelete_cascades() {
        saveRep(1, 80.0, 10.0);
        saveRejected(1, "DEPTH_NOT_CONFIRMED", 40.0, 20.0);
        complete(1);

        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exercise_rejected_attempts WHERE session_id = ?", Integer.class, session.getId());
        assertThat(before).isEqualTo(1);

        sessionService.deleteSession(session.getId(), member.getId());

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exercise_rejected_attempts WHERE session_id = ?", Integer.class, session.getId());
        assertThat(after).isZero();
    }
}
