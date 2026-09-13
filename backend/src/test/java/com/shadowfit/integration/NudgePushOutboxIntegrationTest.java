package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.notification.PushPlatform;
import com.shadowfit.model.notification.PushToken;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import com.shadowfit.repository.notification.PushTokenRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.exercise.OutboxPublisher;
import com.shadowfit.service.notification.push.ExpoPushClient;
import com.shadowfit.service.notification.push.ExpoPushMessage;
import com.shadowfit.service.notification.push.ExpoPushTicket;
import com.shadowfit.service.notification.push.ExpoPushTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재촉 → 아웃박스 → 푸시 발행 — social-cheer-and-group-feed.md §4-3 의 «적재는 같은 트랜잭션, 송신은
 * 발행기» 를 H2 로 통째로 돌린다. Expo 는 {@link ExpoPushClient} 를 mock 으로 바꿔 «바깥 세계» 만 흉내낸다
 * ({@code OutboxPublisherFailureInjectionTest} 의 {@code analysisService} 와 같은 자리).
 *
 * <p>전용 DB·스케줄러 off 인 이유는 그 테스트 주석 그대로 — 다른 컨텍스트의 배경 tick 이 우리 행을 집어 간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nudge_push_outbox;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1"
})
@DisplayName("재촉 → 아웃박스 → 푸시 발행 통합테스트")
class NudgePushOutboxIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PushTokenRepository pushTokenRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private OutboxPublisher publisher;

    @MockitoBean private ExpoPushClient expoPushClient;

    private Member me, friend;
    private Group group;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        me = save("push-me");
        friend = save("push-friend");
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("PUSH0001").createdBy(me).build());
        join(me);
        join(friend);
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        for (Member m : List.of(me, friend)) {
            pushTokenRepository.findAllByMemberId(m.getId()).forEach(pushTokenRepository::delete);
            notificationRepository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(m.getId(), Pageable.unpaged())
                    .forEach(notificationRepository::delete);
        }
        groupMemberRepository.findAllByGroupIdAndStatus(group.getId(), GroupMemberStatus.ACTIVE)
                .forEach(groupMemberRepository::delete);
        groupRepository.delete(group);
        memberRepository.deleteAll(List.of(me, friend));
    }

    @Test
    @DisplayName("수신자에게 기기가 없으면 아웃박스 행을 안 만든다 — 대상 없음은 실패가 아니다(④ c)")
    void noDevice_noOutboxRow() throws Exception {
        nudge();

        assertThat(outboxRepository.findAll()).isEmpty();
        verify(expoPushClient, never()).send(anyList());
    }

    @Test
    @DisplayName("기기가 있으면 알림과 같은 트랜잭션에 PUSH_NOTIFICATION 행 — 발행기가 보내면 SENT, 죽은 토큰은 삭제")
    void device_enqueued_thenDispatched() throws Exception {
        register(friend, "ExponentPushToken[live]");
        register(friend, "ExponentPushToken[dead]");
        when(expoPushClient.send(anyList())).thenReturn(List.of(
                new ExpoPushTicket("ok", "t1", null, null),
                new ExpoPushTicket("error", null, "gone",
                        new ExpoPushTicket.Details(ExpoPushTicket.DEVICE_NOT_REGISTERED))));

        long notificationId = nudge();

        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.PUSH_NOTIFICATION);
        assertThat(row.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_TYPE_NOTIFICATION);
        assertThat(row.getAggregateId()).isEqualTo(notificationId);
        assertThat(row.getPayload()).isEqualTo("{\"notificationId\":" + notificationId + "}");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);

        publisher.dispatchPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpoPushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(expoPushClient).send(captor.capture());
        assertThat(captor.getValue()).extracting(ExpoPushMessage::to)
                .containsExactlyInAnyOrder("ExponentPushToken[live]", "ExponentPushToken[dead]");
        assertThat(captor.getValue().get(0).body()).isEqualTo("push-me님이 오늘 운동을 재촉했어요");

        OutboxEvent after = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();
        assertThat(pushTokenRepository.findAllByMemberId(friend.getId())).extracting(PushToken::getToken)
                .containsExactly("ExponentPushToken[live]");
    }

    @Test
    @DisplayName("Expo 에 닿지 못하면 행은 PENDING 으로 돌아가 백오프 — 유실 없음")
    void transportFailure_retryScheduled() throws Exception {
        register(friend, "ExponentPushToken[live]");
        when(expoPushClient.send(anyList())).thenThrow(new ExpoPushTransportException("down"));

        nudge();
        publisher.dispatchPending();

        OutboxEvent after = outboxRepository.findAll().get(0);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getRetryCount()).isEqualTo(1);
        assertThat(after.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("재촉이 409 로 거절되면(하루 1회) 아웃박스 행도 안 생긴다 — 같은 트랜잭션이라 함께 롤백")
    void duplicateNudge_noSecondRow() throws Exception {
        register(friend, "ExponentPushToken[live]");
        nudge();
        mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isConflict());

        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    // ---------------------------------------------------------------------

    private long nudge() throws Exception {
        String body = mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void register(Member m, String token) {
        pushTokenRepository.saveAndFlush(PushToken.builder().member(m).token(token).platform(PushPlatform.ANDROID).build());
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private void join(Member m) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(m).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
