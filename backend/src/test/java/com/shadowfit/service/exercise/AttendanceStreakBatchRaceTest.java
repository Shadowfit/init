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
 * friend-status-streak-fanout-experiment-design.md §2 후보 b — {@link AttendanceService#currentStreaks} 가
 * 회원마다 부르는 {@link AttendanceService#currentStreak} 과 <b>같은 답</b>을 내는가. 실험은 두 후보의 비용을
 * 비교하는 것이지 답을 비교하는 게 아니므로, 답이 같다는 것은 실험 전에 여기서 못박아 둔다.
 *
 * <p>LATERAL 이 MySQL 8.0.14+ 문법이라 H2 로는 안 돈다 — {@code race} 프로파일(Testcontainers). Docker 가 없으면
 * 건너뜀({@link MySqlContainerSupport}).
 *
 * <p>회원 구성은 배치 경로의 가지를 전부 밟게 짠다: 세션 없음(0) · 오늘부터 3일 · 어제부터 3일(관대한 앵커) ·
 * 그제부터(=0, 앵커 실패) · 중간에 빈 날 · 같은 날 세션 2개 · FETCH_BATCH 를 넘는 연속(배치 한 페이지로 안 끝나
 * 단건 커서로 이어 걷는 가지).
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@DisplayName("후보 b(LATERAL 한 방) streak 가 단건 커서와 같은 답을 낸다 (실 MySQL)")
class AttendanceStreakBatchRaceTest extends MySqlContainerSupport {

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
                .expectedDurationMinutes(15).syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).analysisSupported(true).build());

        Member none = member("none");                       // 세션 없음 → 0
        Member three = member("three");                     // 오늘·어제·그제 → 3
        Member yesterday = member("yesterday");             // 어제·그제·3일전 → 3 (오늘 안 했으면 어제부터)
        Member stale = member("stale");                     // 그제·3일전만 → 0 (앵커 실패)
        Member gap = member("gap");                         // 오늘·어제·(빈 날)·3일전 → 2
        Member twice = member("twice");                     // 오늘 2개·어제 → 2
        Member longRun = member("long");                    // FETCH_BATCH+5 일 연속 → 두 번째 페이지 필요

        for (int i = 0; i < 3; i++) {
            sessionOn(three, today.minusDays(i));
            sessionOn(yesterday, today.minusDays(1 + i));
        }
        sessionOn(stale, today.minusDays(2));
        sessionOn(stale, today.minusDays(3));
        sessionOn(gap, today);
        sessionOn(gap, today.minusDays(1));
        sessionOn(gap, today.minusDays(3));
        sessionOn(twice, today);
        sessionOn(twice, today);
        sessionOn(twice, today.minusDays(1));
        int days = AttendanceService.FETCH_BATCH + 5;
        for (int i = 0; i < days; i++) {
            sessionOn(longRun, today.minusDays(i));
        }
        sessionOn(longRun, today.minusDays(days + 1)); // days 일 전이 비어 있음
        // 방금 저장 안 한 회원의 미래 세션도 오늘 기준 streak 에 안 섞이는지
        sessionOn(none, today.plusDays(1));
    }

    @AfterEach
    void tearDown() {
        List<Long> ids = members.stream().map(Member::getId).toList();
        for (Long id : ids) {
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM exercises WHERE id = ?", exercise.getId());
    }

    @Test
    @DisplayName("일곱 가지 회원 구성 전부 — batch == per-member, 입력 순서 유지, 세션 없는 회원은 0")
    void batchMatchesPerMember() {
        List<Long> ids = members.stream().map(Member::getId).toList();

        Map<Long, Integer> batch = attendanceService.currentStreaks(ids, today);

        assertThat(batch.keySet()).containsExactlyElementsOf(ids);
        for (Member m : members) {
            int single = attendanceService.currentStreak(m.getId(), today);
            assertThat(batch.get(m.getId()))
                    .as("member %s", m.getUsername())
                    .isEqualTo(single);
        }
        // 기대값을 숫자로도 박아 둔다 — 두 경로가 같이 틀리는 경우를 막는다.
        assertThat(batch.values()).containsExactly(0, 3, 3, 0, 2, 2, AttendanceService.FETCH_BATCH + 5);
    }

    @Test
    @DisplayName("빈 입력이면 쿼리 없이 빈 맵")
    void emptyInput() {
        assertThat(attendanceService.currentStreaks(List.of(), today)).isEmpty();
    }

    private Member member(String tag) {
        Member m = memberRepository.saveAndFlush(Member.builder()
                .email("streak-batch-" + tag + "@test.com").username("streak-batch-" + tag).password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        members.add(m);
        return m;
    }

    private void sessionOn(Member m, LocalDate date) {
        sessionRepository.saveAndFlush(Session.builder()
                .member(m).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 20))
                .status(Status.COMPLETED).totalReps(20).avgSyncRate(new BigDecimal("80.00")).build());
    }
}
