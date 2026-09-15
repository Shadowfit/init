package com.shadowfit.integration;

import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.service.exercise.OutboxPublisher;
import com.shadowfit.service.exercise.SessionCompletionTx;
import com.shadowfit.service.group.GroupSocketRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 세션 완료 → 아웃박스 → 모임 자동 글 — social-cheer-and-group-feed.md §4-4 의 «적재는 완료 트랜잭션, 발행은
 * 발행기가 그룹 전부를 한 트랜잭션에» 를 H2 로 통째로 돌린다. 상대가 같은 DB 라 mock 할 바깥이 없다 — 소켓
 * 브로드캐스트만 spy 로 지켜본다.
 *
 * <p>전용 DB·스케줄러 off 인 이유는 {@code NudgePushOutboxIntegrationTest} 와 같다 — 다른 컨텍스트의 배경 tick 이
 * 우리 행을 집어 간다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:session_completed_feed;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1"
})
@DisplayName("세션 완료 → 아웃박스 → 모임 자동 글 통합테스트")
class SessionCompletedFeedOutboxIntegrationTest {

    @Autowired private SessionCompletionTx sessionCompletionTx;
    @Autowired private OutboxPublisher publisher;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private GroupEventRepository groupEventRepository;
    @Autowired private OutboxEventRepository outboxRepository;

    @MockitoSpyBean private GroupSocketRegistry socketRegistry;

    private Member me;
    private Category category;
    private Exercise squat;
    private Group groupA, groupB, groupLeft;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        me = memberRepository.saveAndFlush(Member.builder().email("feed-me@test.com").username("feed-me")
                .password("dummy").role(UserRole.USER).build());
        category = categoryRepository.saveAndFlush(Category.builder().name("LOWER").build());
        squat = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15).syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).analysisSupported(true).build());
        groupA = group("FEEDA001");
        groupB = group("FEEDB001");
        groupLeft = group("FEEDL001");
        join(groupA, GroupMemberStatus.ACTIVE);
        join(groupB, GroupMemberStatus.ACTIVE);
        join(groupLeft, GroupMemberStatus.LEFT);
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        for (Group g : List.of(groupA, groupB, groupLeft)) {
            groupEventRepository.deleteAll(groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(g.getId(), 0L));
            groupMemberRepository.findByGroupIdAndMemberId(g.getId(), me.getId()).ifPresent(groupMemberRepository::delete);
            groupRepository.delete(g);
        }
        reportRepository.deleteAll();
        sessionRepository.deleteAll();
        exercisesRepository.delete(squat);
        categoryRepository.delete(category);
        memberRepository.delete(me);
    }

    @Test
    @DisplayName("완료 트랜잭션은 SESSION_COMPLETED 행만 남긴다 — 글은 아직 없고, AI 재전송에도 행은 1개")
    void complete_enqueuesOnce_noEventsYet() {
        Session session = startSession();

        complete(session);
        complete(session); // AI 콜백 재전송 — complete() 의 멱등 가드가 앞에서 거른다

        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.SESSION_COMPLETED);
        assertThat(row.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_TYPE_SESSION);
        assertThat(row.getAggregateId()).isEqualTo(session.getId());
        assertThat(row.getPayload()).isEqualTo("{\"sessionId\":" + session.getId() + "}");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(eventsOf(groupA)).isEmpty();
        assertThat(eventsOf(groupB)).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 그룹이 하나도 없으면 행을 안 만든다 — 대상 없음은 실패가 아니다(②)")
    void noActiveGroup_noOutboxRow() {
        groupMemberRepository.findByGroupIdAndMemberId(groupA.getId(), me.getId()).ifPresent(gm -> { gm.leave(); groupMemberRepository.saveAndFlush(gm); });
        groupMemberRepository.findByGroupIdAndMemberId(groupB.getId(), me.getId()).ifPresent(gm -> { gm.leave(); groupMemberRepository.saveAndFlush(gm); });

        complete(startSession());

        assertThat(outboxRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("발행기가 ACTIVE 그룹마다 글 하나 — LEFT 그룹엔 없고, sender·source_id·payload 는 §4-4 ①·⑤ 그대로, 커밋 후 브로드캐스트")
    void dispatch_fansOutToActiveGroupsOnly() {
        Session session = startSession();
        complete(session);

        publisher.dispatchPending();

        OutboxEvent after = outboxRepository.findAll().get(0);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();

        for (Group g : List.of(groupA, groupB)) {
            List<GroupEvent> events = eventsOf(g);
            assertThat(events).hasSize(1);
            GroupEvent e = events.get(0);
            assertThat(e.getEventType()).isEqualTo(GroupEventTypes.SESSION_COMPLETED);
            assertThat(e.getSeq()).isEqualTo(1L);
            assertThat(e.getSourceId()).isEqualTo(session.getId());
            assertThat(e.getSender().getId()).isEqualTo(me.getId());
            assertThat(e.getPayload()).isEqualTo("{\"sessionId\":" + session.getId()
                    + ",\"memberId\":" + me.getId() + ",\"username\":\"feed-me\",\"exerciseName\":\"스쿼트\"}");
            verify(socketRegistry).broadcast(eq(g.getId()), anyString());
        }
        assertThat(eventsOf(groupLeft)).isEmpty();
    }

    @Test
    @DisplayName("같은 행이 다시 배달돼도(회수분) 글은 그룹당 1개 그대로 — source_id 로 걸러진다(④ c)")
    void redelivery_doesNotDuplicate() {
        Session session = startSession();
        complete(session);
        publisher.dispatchPending();
        assertThat(eventsOf(groupA)).hasSize(1);

        // lease 상실 뒤 회수된 것처럼 — 같은 행을 PENDING 으로 되돌려 다시 집게 한다
        OutboxEvent row = outboxRepository.findAll().get(0);
        row.setStatus(OutboxStatus.PENDING);
        row.setSentAt(null);
        outboxRepository.saveAndFlush(row);

        publisher.dispatchPending();

        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(eventsOf(groupA)).hasSize(1);
        assertThat(eventsOf(groupB)).hasSize(1);
        assertThat(eventsOf(groupA).get(0).getSeq()).isEqualTo(1L);
    }

    @Test
    @DisplayName("적재 뒤 전부 탈퇴하면 TERMINAL_FAILED — 재시도해도 같다(⑥)")
    void allLeftAfterEnqueue_terminalFailed() {
        Session session = startSession();
        complete(session);
        groupMemberRepository.findByGroupIdAndMemberId(groupA.getId(), me.getId()).ifPresent(gm -> { gm.leave(); groupMemberRepository.saveAndFlush(gm); });
        groupMemberRepository.findByGroupIdAndMemberId(groupB.getId(), me.getId()).ifPresent(gm -> { gm.leave(); groupMemberRepository.saveAndFlush(gm); });

        publisher.dispatchPending();

        assertThat(outboxRepository.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(eventsOf(groupA)).isEmpty();
    }

    @Test
    @DisplayName("적재 뒤 세션이 지워지면 TERMINAL_FAILED(⑥) — aggregate_id 에 FK 가 없어 행은 남고 판정만 종료")
    void sessionDeletedAfterEnqueue_terminalFailed() {
        Session session = startSession();
        complete(session);
        reportRepository.deleteAll();
        sessionRepository.deleteById(session.getId());

        publisher.dispatchPending();

        assertThat(outboxRepository.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.FAILED);
        verify(socketRegistry, org.mockito.Mockito.never()).broadcast(anyLong(), anyString());
    }

    // ---------------------------------------------------------------------

    private Session startSession() {
        return sessionRepository.saveAndFlush(Session.builder().member(me).exercise(squat)
                .startTime(LocalDateTime.now().minusMinutes(10)).status(Status.IN_PROGRESS).build());
    }

    private void complete(Session session) {
        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId()).setTotalReps(3).setAvgSyncRate(80).setMaxSyncRate(90)
                .setMinSyncRate(70).setCaloriesBurned(10.0).build());
    }

    private List<GroupEvent> eventsOf(Group g) {
        return groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(g.getId(), 0L);
    }

    private Group group(String code) {
        return groupRepository.saveAndFlush(Group.builder().name("그룹 " + code).inviteCode(code).createdBy(me).build());
    }

    private void join(Group g, GroupMemberStatus status) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(g).member(me).role(GroupRole.MEMBER).status(status).build());
    }
}
