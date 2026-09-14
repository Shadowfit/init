package com.shadowfit.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import com.shadowfit.service.notification.NotificationRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재촉의 WebSocket 즉시 전달(§4-1 #7)을 실제 연결로 — 수신자가 모임 화면에 붙어 있으면
 * {@code NOTIFICATION} 프레임이 <b>그 사람에게만</b> 간다. 같은 그룹 소켓에 붙어 있는 보낸 사람은
 * 못 받는다(그룹 채널이 아니라 회원 인덱스로 가므로).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("재촉 WebSocket 1:1 전달 통합테스트")
class NudgeWebSocketRelayIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member me, friend;
    private Group group;

    @BeforeEach
    void setUp() {
        me = save("relay-me");
        friend = save("relay-friend");
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("RELAY001").createdBy(me).build());
        join(me);
        join(friend);
    }

    @AfterEach
    void tearDown() {
        for (Member m : List.of(me, friend)) {
            notificationRepository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(m.getId(), Pageable.unpaged())
                    .forEach(notificationRepository::delete);
        }
        // group_members 는 workout_groups 에 CASCADE(V12) — 그룹만 지우면 된다
        groupRepository.delete(group);
        memberRepository.deleteAll(List.of(me, friend));
    }

    @Test
    @DisplayName("둘 다 그룹 소켓에 붙어 있을 때 재촉 → 수신자만 NOTIFICATION 프레임을 받는다")
    void nudge_relaysOnlyToRecipientSocket() throws Exception {
        try (TestWsClient friendWs = TestWsClient.connect(port, group.getId(), tokenFor(friend));
             TestWsClient myWs = TestWsClient.connect(port, group.getId(), tokenFor(me))) {

            mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                            .header("Authorization", "Bearer " + tokenFor(me)))
                    .andExpect(status().isCreated());

            String frame = friendWs.frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("수신자 소켓에 프레임이 와야 한다").isNotNull();
            JsonNode json = objectMapper.readTree(frame);
            assertThat(json.path("type").asText()).isEqualTo(NotificationRelay.FRAME_TYPE);
            assertThat(json.path("notification").path("type").asText()).isEqualTo("NUDGE");
            assertThat(json.path("notification").path("senderId").asLong()).isEqualTo(me.getId());
            assertThat(json.path("notification").path("senderUsername").asText()).isEqualTo("relay-me");
            assertThat(json.path("notification").path("read").asBoolean()).isFalse();
            // 그룹 이벤트 봉투와 달리 seq·groupId 가 없다 — 클라이언트는 type 으로 가른다
            assertThat(json.has("seq")).isFalse();

            // 보낸 사람 소켓엔 안 간다 — 잠깐 기다려도 비어 있어야 한다
            assertThat(myWs.frames.poll(1, TimeUnit.SECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("수신자가 안 붙어 있으면 아무 데도 안 가지만 재촉(저장)은 성공한다")
    void nudge_offlineRecipient_stillCreated() throws Exception {
        try (TestWsClient myWs = TestWsClient.connect(port, group.getId(), tokenFor(me))) {
            mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                            .header("Authorization", "Bearer " + tokenFor(me)))
                    .andExpect(status().isCreated());
            assertThat(myWs.frames.poll(1, TimeUnit.SECONDS)).isNull();
        }
        assertThat(notificationRepository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(friend.getId(), Pageable.unpaged())
                .getTotalElements()).isEqualTo(1);
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

    /** JDK 내장 {@link WebSocket} 클라이언트 — 받은 텍스트 프레임을 큐에 모은다. */
    private static final class TestWsClient implements AutoCloseable {
        final BlockingQueue<String> frames;
        private final WebSocket socket;

        private TestWsClient(WebSocket socket, BlockingQueue<String> frames) {
            this.socket = socket;
            this.frames = frames;
        }

        static TestWsClient connect(int port, Long groupId, String token) throws Exception {
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            StringBuilder partial = new StringBuilder();
            URI uri = URI.create("ws://localhost:" + port + "/ws/groups/" + groupId + "?token=" + token);
            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(uri, new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            partial.append(data);
                            if (last) {
                                frames.add(partial.toString());
                                partial.setLength(0);
                            }
                            webSocket.request(1);
                            return null;
                        }
                    })
                    .get(5, TimeUnit.SECONDS);
            return new TestWsClient(ws, frames);
        }

        @Override
        public void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").join();
        }
    }
}
