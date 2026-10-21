package com.shadowfit.service.exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.RejectedAttemptRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.RejectedAttempt;
import com.shadowfit.model.exercise.RejectedAttemptReason;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.RejectedAttemptRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «미인정 시도» 수신 (V35, {@link RejectedAttemptService}) — H2 위에서 멱등·검증·세션 상태 규칙을 고정한다.
 * 실 MySQL 의 JSON 컬럼·FK CASCADE 는 {@code RejectedAttemptRaceTest} 가 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("미인정 시도 수신 테스트 (V35)")
class RejectedAttemptServiceTest {

    @Autowired private RejectedAttemptService rejectedAttemptService;
    @Autowired private RejectedAttemptRepository rejectedAttemptRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    // 발행기가 테스트 도중 돌면 검증이 흔들린다 (SessionSyncStatsTest 와 같은 이유·같은 컨텍스트).
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Session session;
    private Category category;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("rejected@test.com").username("rejected").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true).build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(5))
                .status(Status.IN_PROGRESS).build());
    }

    private RejectedAttemptRequest.Builder attempt(int attemptNumber) {
        return RejectedAttemptRequest.newBuilder()
                .setSessionId(session.getId())
                .setAttemptNumber(attemptNumber)
                .setReason("DEPTH_NOT_CONFIRMED")
                .setSyncRate(46.5)
                .setMinKneeAngle(101.5)
                .setTorsoTiltDeg(28.0)
                .setTimestampSec(12.4)
                .setJointCoordinates("[{\"index\":25,\"x\":0.5,\"y\":0.5}]");
    }

    private List<RejectedAttempt> stored() {
        return rejectedAttemptRepository.findAll().stream()
                .filter(r -> r.getSession().getId().equals(session.getId()))
                .toList();
    }

    @Test
    @DisplayName("저장된다 — 값이 그대로 남고 last_active_at 이 갱신된다")
    void stores() {
        boolean saved = rejectedAttemptService.save(attempt(1).build());

        assertThat(saved).isTrue();
        List<RejectedAttempt> rows = stored();
        assertThat(rows).hasSize(1);
        RejectedAttempt row = rows.get(0);
        assertThat(row.getAttemptNo()).isEqualTo(1);
        assertThat(row.getReason()).isEqualTo(RejectedAttemptReason.DEPTH_NOT_CONFIRMED);
        assertThat(row.getSyncRate()).isEqualTo(46.5);
        assertThat(row.getMinKneeAngle()).isEqualTo(101.5);
        assertThat(row.getTorsoTiltDeg()).isEqualTo(28.0);
        assertThat(row.getTimestampSec()).isEqualTo(12.4);
        assertThat(row.getCreatedAt()).isNotNull();
        // 얕은 시도만 반복하는 동안에도 유휴 타임아웃이 세션을 걷어가지 않게
        LocalDateTime lastActive = jdbcTemplate.queryForObject(
                "SELECT last_active_at FROM exercise_sessions WHERE id = ?", LocalDateTime.class, session.getId());
        assertThat(lastActive).isNotNull();
    }

    @Test
    @DisplayName("같은 (세션, 시도 번호) 재전송은 두 번째 행을 만들지 않고 성공(false)으로 끝난다")
    void idempotent() {
        assertThat(rejectedAttemptService.save(attempt(1).build())).isTrue();
        // 재전송 — 값이 달라도(재채점 등) 첫 저장을 보존한다
        assertThat(rejectedAttemptService.save(attempt(1).setSyncRate(10.0).build())).isFalse();
        assertThat(rejectedAttemptService.save(attempt(2).build())).isTrue();

        assertThat(stored()).extracting(RejectedAttempt::getAttemptNo).containsExactlyInAnyOrder(1, 2);
        assertThat(stored()).filteredOn(r -> r.getAttemptNo() == 1)
                .extracting(RejectedAttempt::getSyncRate).containsExactly(46.5);
        assertThat(rejectedAttemptRepository.countBySessionId(session.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("무릎각 0(proto3 미전송)은 «미상» 이라 NULL, 좌표가 비면 NULL")
    void zeroKneeAndBlankJoints_storedAsNull() {
        rejectedAttemptService.save(attempt(1).setMinKneeAngle(0.0).setJointCoordinates("").build());

        RejectedAttempt row = stored().get(0);
        assertThat(row.getMinKneeAngle()).isNull();
        assertThat(row.getJointCoordinates()).isNull();
    }

    @Test
    @DisplayName("SPLIT_STANCE 도 받는다")
    void splitStance() {
        rejectedAttemptService.save(attempt(1).setReason("SPLIT_STANCE").build());

        assertThat(stored().get(0).getReason()).isEqualTo(RejectedAttemptReason.SPLIT_STANCE);
    }

    @Test
    @DisplayName("형식 위반(시도 번호 0·모르는 사유)은 INVALID_INPUT_VALUE — 행이 안 생긴다")
    void malformed_rejected() {
        assertThatThrownBy(() -> rejectedAttemptService.save(attempt(0).build()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        assertThatThrownBy(() -> rejectedAttemptService.save(attempt(1).setReason("TOO_FAST").build()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
        assertThatThrownBy(() -> rejectedAttemptService.save(attempt(1).setReason("").build()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);

        assertThat(stored()).isEmpty();
    }

    @Test
    @DisplayName("범위 위반(싱크로율·시각·각도·좌표 JSON)은 DATA_INTEGRITY_VIOLATION — 행이 안 생긴다")
    void outOfRange_rejected() {
        List<RejectedAttemptRequest> bad = List.of(
                attempt(1).setSyncRate(100.5).build(),
                attempt(1).setSyncRate(-1.0).build(),
                attempt(1).setSyncRate(Double.NaN).build(),
                attempt(1).setTimestampSec(-0.1).build(),
                attempt(1).setTimestampSec(3600.5).build(),
                attempt(1).setMinKneeAngle(-5.0).build(),
                attempt(1).setMinKneeAngle(181.0).build(),
                attempt(1).setTorsoTiltDeg(-1.0).build(),
                attempt(1).setJointCoordinates("{이건 JSON 이 아님").build());
        for (RejectedAttemptRequest request : bad) {
            assertThatThrownBy(() -> rejectedAttemptService.save(request))
                    .as(request.toString())
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        assertThat(stored()).isEmpty();
    }

    @Test
    @DisplayName("경계값(싱크로율 0·100, 시각 0)은 받는다")
    void boundaries_accepted() {
        assertThat(rejectedAttemptService.save(attempt(1).setSyncRate(0.0).setTimestampSec(0.0).build())).isTrue();
        assertThat(rejectedAttemptService.save(attempt(2).setSyncRate(100.0).build())).isTrue();
    }

    @Test
    @DisplayName("세션이 없으면 SESSION_NOT_FOUND")
    void unknownSession() {
        assertThatThrownBy(() -> rejectedAttemptService.save(attempt(1).setSessionId(987654L).build()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("종료된 세션에 늦게 온 시도는 조용히 버린다(성공, 행 없음) — pose 배치와 같은 규칙")
    void endedSession_dropped() {
        for (Status ended : List.of(Status.COMPLETED, Status.FAILED, Status.CANCELLED)) {
            Session endedSession = sessionRepository.saveAndFlush(Session.builder()
                    .member(session.getMember()).exercise(session.getExercise())
                    .startTime(LocalDateTime.now().minusMinutes(3))
                    .status(ended).build());

            boolean saved = rejectedAttemptService.save(attempt(1).setSessionId(endedSession.getId()).build());

            assertThat(saved).as(ended.name()).isFalse();
            assertThat(rejectedAttemptRepository.countBySessionId(endedSession.getId())).as(ended.name()).isZero();
        }
    }

    @Test
    @DisplayName("상태 확인 뒤 쓰기 직전에 세션이 끝났으면(완료 콜백과의 경합) 버린다 — UPDATE 의 status 조건")
    void endedBetweenCheckAndWrite_dropped() {
        // 영속성 컨텍스트의 세션은 IN_PROGRESS 로 남겨 둔 채 DB 만 COMPLETED 로 바꾼다 — «잠금 없는 상태 확인은 통과했는데
        // 그 사이 완료 콜백이 커밋된» 순간을 그대로 만든다.
        jdbcTemplate.update("UPDATE exercise_sessions SET status = 'COMPLETED' WHERE id = ?", session.getId());

        boolean saved = rejectedAttemptService.save(attempt(1).build());

        assertThat(saved).isFalse();
        assertThat(stored()).isEmpty();
    }

    @Test
    @DisplayName("횟수만 세는 종목은 점수가 없어 저장하지 않는다")
    void countOnlyExercise_dropped() {
        Exercise countOnly = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("버피").category(category).expectedDurationMinutes(10)
                .repetitionOnly(true).build());
        Session countOnlySession = sessionRepository.saveAndFlush(Session.builder()
                .member(session.getMember()).exercise(countOnly)
                .startTime(LocalDateTime.now().minusMinutes(3))
                .status(Status.IN_PROGRESS).build());

        boolean saved = rejectedAttemptService.save(attempt(1).setSessionId(countOnlySession.getId()).build());

        assertThat(saved).isFalse();
        assertThat(rejectedAttemptRepository.countBySessionId(countOnlySession.getId())).isZero();
    }
}
