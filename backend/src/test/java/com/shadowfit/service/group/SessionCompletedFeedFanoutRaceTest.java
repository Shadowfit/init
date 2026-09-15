package com.shadowfit.service.group;

import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * social-cheer-and-group-feed.md §4-4 ⑧ — 자동 글 팬아웃의 잠금 순서 규약(③ b)이 실 MySQL 에서 데드락 없이
 * 도는가. 한 트랜잭션이 그룹 행을 둘 이상 {@code FOR UPDATE} 로 잠그는 곳은 이 팬아웃이 처음이라, 두 회원이
 * 같은 그룹 둘 {A, B} 에 ACTIVE 인 상태에서 두 세션의 발행을 동시에 돌린다.
 *
 * <p><b>왜 H2 로는 안 되는가</b> — H2 의 행 잠금·데드락 감지는 InnoDB 와 다르다(MVCC 모드에서 락 대기 대신
 * 타임아웃으로 푸는 경우가 있다). «오름차순이면 순환이 없다» 는 명제는 InnoDB 의 next-key 락 위에서만 검증된다.
 * 같은 이유로 Flyway V19(source_id + UNIQUE)가 엔티티와 맞는지도 이 프로파일({@code ddl-auto: validate})이
 * 처음 본다.
 *
 * <p>Docker 가 없으면 «건너뜀» ({@link MySqlContainerSupport}). 로컬에서 도는 법:
 * <pre>
 *   ./gradlew :backend:test --tests '*SessionCompletedFeedFanoutRaceTest'
 * </pre>
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@DisplayName("§4-4 ⑧ 자동 글 팬아웃 동시 발행 — 잠금 순서 규약 (실 MySQL)")
class SessionCompletedFeedFanoutRaceTest extends MySqlContainerSupport {

    /** 두 스레드가 «같은 순간» 을 맞추는 시도 횟수 — 1판이면 순서가 우연히 안 겹칠 수 있다. */
    private static final int ROUNDS = 5;

    @Autowired private SessionCompletedFeedService feedService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Member alice, bob;
    private Exercise exercise;
    private Group groupA, groupB;

    @BeforeEach
    void setUp() {
        alice = memberRepository.saveAndFlush(Member.builder().email("fanout-alice@test.com").username("fanout-alice")
                .password("dummy").selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        bob = memberRepository.saveAndFlush(Member.builder().email("fanout-bob@test.com").username("fanout-bob")
                .password("dummy").selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.findByName("LOWER")
                .orElseGet(() -> categoryRepository.save(Category.builder().name("LOWER").build()));
        exercise = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15).syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).build());
        groupA = groupRepository.saveAndFlush(Group.builder().name("A").inviteCode("FANOUTA1").createdBy(alice).build());
        groupB = groupRepository.saveAndFlush(Group.builder().name("B").inviteCode("FANOUTB1").createdBy(bob).build());
        for (Member m : List.of(alice, bob)) {
            for (Group g : List.of(groupA, groupB)) {
                groupMemberRepository.saveAndFlush(GroupMember.builder().group(g).member(m)
                        .role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build());
            }
        }
    }

    @AfterEach
    void tearDown() {
        // ddl-auto: validate 프로파일이라 @Transactional 롤백에 기대지 않고 직접 지운다. 그룹은 CASCADE 로
        // 이벤트·멤버십을 데려가고, 회원은 세션을 데려간다.
        jdbcTemplate.update("DELETE FROM workout_groups WHERE id IN (?, ?)", groupA.getId(), groupB.getId());
        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id IN (?, ?)", alice.getId(), bob.getId());
        jdbcTemplate.update("DELETE FROM exercises WHERE id = ?", exercise.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", alice.getId(), bob.getId());
    }

    @Test
    @DisplayName("두 회원의 세션 발행이 동시에 같은 그룹 둘을 잠가도 — 둘 다 SENT, 글은 그룹당 회원당 1개, 재발행해도 그대로")
    void concurrentFanout_noDeadlock_noDuplicate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                Session sa = completedSession(alice);
                Session sb = completedSession(bob);

                CountDownLatch start = new CountDownLatch(1);
                List<Future<DispatchOutcome>> results = new ArrayList<>();
                for (Session s : List.of(sa, sb)) {
                    results.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return feedService.dispatch(s.getId());
                    }));
                }
                start.countDown();
                for (Future<DispatchOutcome> f : results) {
                    assertThat(f.get(60, TimeUnit.SECONDS))
                            .as("round %d — 오름차순 잠금이면 한쪽이 줄을 설 뿐 데드락(RETRY)이 없어야 한다", round)
                            .isEqualTo(DispatchOutcome.SENT);
                }
                assertThat(countFeed(sa.getId())).as("round %d alice", round).isEqualTo(2);
                assertThat(countFeed(sb.getId())).as("round %d bob", round).isEqualTo(2);

                // 회수분 재배달 — 같은 세션으로 다시 돌려도 글이 늘지 않는다(④ c).
                assertThat(feedService.dispatch(sa.getId())).isEqualTo(DispatchOutcome.SENT);
                assertThat(countFeed(sa.getId())).isEqualTo(2);
            }
            // 그룹 seq 는 그룹 안에서 유일·연속이어야 한다(uk_group_events_group_seq) — 잠금이 깨졌으면 여기서 드러난다.
            for (Group g : List.of(groupA, groupB)) {
                Integer rows = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM group_events WHERE group_id = ?", Integer.class, g.getId());
                Long maxSeq = jdbcTemplate.queryForObject(
                        "SELECT MAX(seq) FROM group_events WHERE group_id = ?", Long.class, g.getId());
                assertThat(rows).isEqualTo(ROUNDS * 2);
                assertThat(maxSeq).isEqualTo((long) ROUNDS * 2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Session completedSession(Member owner) {
        return sessionRepository.saveAndFlush(Session.builder().member(owner).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(10)).endTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(3).avgSyncRate(new BigDecimal("80.00")).build());
    }

    private int countFeed(Long sessionId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_events WHERE event_type = 'SESSION_COMPLETED' AND source_id = ?",
                Integer.class, sessionId);
        return n == null ? 0 : n;
    }
}
