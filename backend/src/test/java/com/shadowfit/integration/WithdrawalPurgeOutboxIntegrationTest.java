package com.shadowfit.integration;

import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.exercise.OutboxPublisher;
import com.shadowfit.service.member.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 → 삭제 이벤트가 같은 커밋으로 적재되고, 발행기가 집어 처리하는 흐름 전체.
 *
 * <p>예전에는 탈퇴 커밋 뒤 {@code @Async} 한 번이라 실패하면 끝이었다. 지금은 아웃박스 행이라 발행기가 지울 때까지 남는다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 발행기는 {@code REQUIRES_NEW} 로 자기 트랜잭션을 열어서, 테스트 트랜잭션 안의
 * 미커밋 행을 보지 못한다(다른 아웃박스 통합 테스트와 같은 이유). 대신 전용 H2 와 {@code tearDown} 정리를 쓴다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:withdrawal_purge;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1"
})
@DisplayName("탈퇴 → 삭제 이벤트(PURGE_MEMBER_DATA) 아웃박스")
class WithdrawalPurgeOutboxIntegrationTest {

    @Autowired private MemberService memberService;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Member member;
    private Session session;
    private Category category;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        member = memberRepository.saveAndFlush(Member.builder()
                .email("purge@test.com").username("purgeuser").password("dummy").role(UserRole.USER).build());
        category = categoryRepository.saveAndFlush(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15).build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusHours(1)).endTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(10).build());
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        sessionRepository.deleteAll();
        exercisesRepository.delete(exercise);
        categoryRepository.delete(category);
        memberRepository.findByEmail("purge@test.com").ifPresent(memberRepository::delete);
        memberRepository.findByEmail("plain@test.com").ifPresent(memberRepository::delete);
    }

    private List<OutboxEvent> purgeEvents() {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getEventType() == OutboxEventType.PURGE_MEMBER_DATA).toList();
    }

    @Test
    @DisplayName("탈퇴하면 세션 id 가 담긴 삭제 이벤트가 PENDING 으로 적재되고, 발행기가 집으면 SENT 가 된다")
    void withdrawal_enqueuesPurgeEvent_andPublisherCompletesIt() {
        Long memberId = member.getId();
        Long sessionId = session.getId();

        memberService.deleteAccount("purge@test.com");

        List<OutboxEvent> events = purgeEvents();
        assertThat(events).hasSize(1);
        OutboxEvent e = events.get(0);
        assertThat(e.getAggregateType()).isEqualTo("MEMBER");
        assertThat(e.getAggregateId()).isEqualTo(memberId);
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e.getPayload()).contains("\"sessionIds\"").contains(String.valueOf(sessionId));
        assertThat(memberRepository.findByEmail("purge@test.com")).isEmpty();

        outboxPublisher.dispatchPending();

        assertThat(purgeEvents()).singleElement()
                .satisfies(done -> assertThat(done.getStatus()).isEqualTo(OutboxStatus.SENT));
    }

    @Test
    @DisplayName("세션도 사진도 없는 회원은 삭제 이벤트를 만들지 않는다")
    void withdrawal_nothingToPurge_noEvent() {
        Member plain = memberRepository.saveAndFlush(Member.builder()
                .email("plain@test.com").username("plainuser").password("dummy").role(UserRole.USER).build());

        memberService.deleteAccount(plain.getEmail());

        assertThat(purgeEvents()).isEmpty();
    }
}
