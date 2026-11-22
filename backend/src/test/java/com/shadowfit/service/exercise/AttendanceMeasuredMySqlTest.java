package com.shadowfit.service.exercise;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «측정된 것이 없는 세션은 출석에서 제외» (사용자 결정 2026-11-22) 를 <b>실 MySQL</b> 에서 확인한다 — H2 로는 안 보이는 둘:
 * ① 네이티브 LATERAL 배치({@code findCompletedStartTimesBeforeBatch})가 같은 조건을 거는가 ② 조건을 더한 연속 출석 첫 페이지 쿼리가
 * 여전히 {@code idx_session_member_status_start} 를 뒤에서부터 걷고 filesort 를 안 쓰는가.
 *
 * <p>②는 이 저장소가 한 번 데인 자리다 — 첫 페이지 술어 모양 하나가 계획을 ref + filesort 로 바꿔 읽는 행이 streak 길이가 아니라
 * 회원의 세션 수가 됐다(#761). 비-인덱스 컬럼 조건을 더하는 이 변경이 같은 일을 만들지 않는지 계획으로 본다.
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@DisplayName("출석에서 측정 안 된 세션 제외 (실 MySQL)")
class AttendanceMeasuredMySqlTest extends MySqlContainerSupport {

    @Autowired private AttendanceService attendanceService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final LocalDate today = LocalDate.now();
    private final List<Member> members = new ArrayList<>();
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        Category category = categoryRepository.findByName("LOWER")
                .orElseGet(() -> categoryRepository.save(Category.builder().name("LOWER").build()));
        exercise = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15).analysisSupported(true).build());
    }

    @AfterEach
    void tearDown() {
        for (Member m : members) {
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id = ?", m.getId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", m.getId());
        }
        jdbcTemplate.update("DELETE FROM exercises WHERE id = ?", exercise.getId());
    }

    @Test
    @DisplayName("LATERAL 배치도 측정 안 된 세션을 건너뛴다 — 배치 == 단건, 구멍은 연속을 끊는다")
    void batchSkipsUnmeasured() {
        Member solid = member("solid");        // 오늘·어제·그제 모두 측정됨 → 3
        Member hole = member("hole");          // 오늘 측정 · 어제 «측정 안 됨» · 그제 측정 → 1 (어제가 구멍)
        Member onlyEmpty = member("empty");    // 오늘·어제 모두 측정 안 됨 → 0
        Member attemptsOnly = member("tries"); // 오늘 인정 0회지만 점수 있음(미인정 시도) → 1

        for (int i = 0; i < 3; i++) measured(solid, today.minusDays(i));
        measured(hole, today);
        unmeasured(hole, today.minusDays(1));
        measured(hole, today.minusDays(2));
        unmeasured(onlyEmpty, today);
        unmeasured(onlyEmpty, today.minusDays(1));
        sessionRepository.saveAndFlush(session(attemptsOnly, today, 0, new BigDecimal("42.50")));

        List<Long> ids = members.stream().map(Member::getId).toList();
        Map<Long, Integer> batch = attendanceService.currentStreaks(ids, today);

        assertThat(batch.values()).containsExactly(3, 1, 0, 1);
        for (Member m : members) {
            assertThat(batch.get(m.getId())).as(m.getUsername())
                    .isEqualTo(attendanceService.currentStreak(m.getId(), today));
        }
    }

    @Test
    @DisplayName("연속 출석 첫 페이지 쿼리가 조건을 더하고도 idx_session_member_status_start 를 타고 filesort 가 없다 (계획 점검)")
    void firstPagePlanStillWalksTheIndex() {
        Member heavy = member("heavy");
        // 2,000세션 — #761 이 계획이 뒤집힌 것을 확인한 규모. 측정됨 80% · 안 됨 20% 를 섞는다.
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            boolean measured = i % 5 != 0;
            // 하루 두 세션 — i 가 짝수면 09시, 홀수면 11시. i%5==0 만 «측정 안 됨» 이라 두 세션이 모두 안 된 날은 없다.
            sessions.add(Session.builder().member(heavy).exercise(exercise)
                    .startTime(today.minusDays(i / 2).atTime(i % 2 == 0 ? 9 : 11, 0))
                    .endTime(today.minusDays(i / 2).atTime(i % 2 == 0 ? 9 : 11, 20))
                    .status(Status.COMPLETED).totalReps(measured ? 12 : 0)
                    .avgSyncRate(measured ? new BigDecimal("75.00") : null).build());
        }
        // 다른 회원들의 세션도 깔아 «전체 COMPLETED 를 status 로 훑는» 계획이 손해인 상황을 만든다 — 회원 1명뿐이면 두 인덱스가 같아 보인다.
        for (int k = 0; k < 30; k++) {
            Member extra = member("bg" + k);
            List<Session> bg = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                boolean m = i % 5 != 0;
                bg.add(Session.builder().member(extra).exercise(exercise)
                        .startTime(today.minusDays(i / 2).atTime(i % 2 == 0 ? 9 : 11, 0))
                        .endTime(today.minusDays(i / 2).atTime(i % 2 == 0 ? 9 : 11, 20))
                        .status(Status.COMPLETED).totalReps(m ? 12 : 0)
                        .avgSyncRate(m ? new BigDecimal("75.00") : null).build());
            }
            sessionRepository.saveAll(bg);
        }
        sessionRepository.saveAllAndFlush(sessions);
        jdbcTemplate.execute("ANALYZE TABLE exercise_sessions");

        // 기준선 — 조건을 더하기 전의 첫 페이지 쿼리(#761 이 재현한 모양).
        Map<String, Object> baseline = jdbcTemplate.queryForList(
                "EXPLAIN SELECT start_time FROM exercise_sessions WHERE member_id = ? AND status = 'COMPLETED' "
                        + "ORDER BY start_time DESC LIMIT 31", heavy.getId()).get(0);

        // 하이버네이트가 내는 모양 그대로 — AttendanceService.currentStreakRun 의 첫 페이지(findLatestCompletedStartTimes).
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT start_time FROM exercise_sessions WHERE member_id = ? AND status = 'COMPLETED' "
                        + "AND (total_reps > 0 OR avg_sync_rate IS NOT NULL) ORDER BY start_time DESC LIMIT 31",
                heavy.getId());
        System.out.println("PLAN-BASELINE " + baseline);
        System.out.println("PLAN-WITH-PREDICATE " + plan.get(0));

        assertThat(plan).hasSize(1);
        Map<String, Object> row = plan.get(0);
        assertThat(String.valueOf(row.get("key"))).as("EXPLAIN key: %s", row).isEqualTo("idx_session_member_status_start");
        assertThat(String.valueOf(row.get("Extra"))).as("EXPLAIN Extra: %s", row).doesNotContain("filesort");

        // 답도 맞다 — 오늘이 측정됨이므로 streak 는 «측정된 날» 만큼(안 된 세션은 같은 날 다른 시각에 측정된 세션이 있어 영향 없음).
        assertThat(attendanceService.currentStreak(heavy.getId(), today)).isEqualTo(1000);
    }

    private Member member(String tag) {
        Member m = memberRepository.saveAndFlush(Member.builder()
                .email("att-measured-" + tag + "@test.com").username("att-measured-" + tag).password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        members.add(m);
        return m;
    }

    private void measured(Member m, LocalDate date) {
        sessionRepository.saveAndFlush(session(m, date, 10, new BigDecimal("80.00")));
    }

    private void unmeasured(Member m, LocalDate date) {
        sessionRepository.saveAndFlush(session(m, date, 0, null));
    }

    private Session session(Member m, LocalDate date, int totalReps, BigDecimal avg) {
        return Session.builder().member(m).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 20))
                .status(Status.COMPLETED).totalReps(totalReps).avgSyncRate(avg).build();
    }
}
