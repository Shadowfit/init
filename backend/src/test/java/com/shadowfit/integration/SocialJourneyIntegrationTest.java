package com.shadowfit.integration;

import com.jayway.jsonpath.JsonPath;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.grpc.SessionCompleteResponse;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.exercise.ExerciseGrpcService;
import com.shadowfit.service.exercise.OutboxPublisher;
import com.shadowfit.service.notification.push.ExpoPushClient;
import com.shadowfit.service.notification.push.ExpoPushMessage;
import com.shadowfit.service.notification.push.ExpoPushTicket;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소셜 L1 저니 — social-cheer-and-group-feed.md §4-1 #12. 기능 여섯(코드 참여 → 세션 완료 → 자동 글 → 리액션 →
 * 현황 → 재촉 → 푸시·알림함)의 <b>이음새</b>만 본다: 앞 단계가 만든 데이터를 뒷 단계가 그대로 읽는가. 각 기능의
 * 가지(403/409/더블탭/회수분 재배달)는 기능별 테스트가 이미 밟았으므로 여기서 반복하지 않는다.
 *
 * <p>경계는 실제로(HTTP → 서비스 → H2), 바깥 세계만 가짜로 — AI 는 gRPC servicer 를 자바에서 직접 호출
 * ({@code ExerciseSessionFlowIntegrationTest} 선례), Expo 는 {@link ExpoPushClient} mock. 아웃박스 발행기는 tick 을
 * 기다리지 않고 {@code dispatchPending()} 으로 당긴다. REQUIRES_NEW 경로(아웃박스·리액션·토큰)가 있어 {@code @Transactional}
 * 롤백에 기대지 않고 직접 지운다.
 *
 * <p>한 서사 = 메서드 하나, 이음새마다 단언. 변형은 {@link AfterLeave} 하나 — 탈퇴가 여러 기능에 동시에 걸치는 유일한
 * 이음새라서.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:social_journey;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1"
})
@DisplayName("소셜 저니 — 코드 참여 → 완료 → 자동 글 → 리액션 → 현황 → 재촉 → 푸시·알림함 (§4-1 #12)")
class SocialJourneyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private ExerciseGrpcService grpcService;
    @Autowired private OutboxPublisher publisher;

    @MockitoBean private ExpoPushClient expoPushClient;

    private Member minsu, cheolsu;
    private Category category;
    private Exercise squat;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        minsu = save("민수");
        cheolsu = save("철수");
        category = categoryRepository.saveAndFlush(Category.builder().name("LOWER").build());
        squat = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15).syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).analysisSupported(true).build());
    }

    @AfterEach
    void tearDown() {
        // FK CASCADE 가 대부분을 데려간다 — 그룹(이벤트·멤버십·리액션), 회원(세션·리포트·일지·알림·토큰).
        outboxRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM workout_groups WHERE created_by IN (?, ?)", minsu.getId(), cheolsu.getId());
        jdbcTemplate.update("DELETE FROM session_reports WHERE member_id IN (?, ?)", minsu.getId(), cheolsu.getId());
        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id IN (?, ?)", minsu.getId(), cheolsu.getId());
        memberRepository.deleteAll(List.of(minsu, cheolsu));
        exercisesRepository.delete(squat);
        categoryRepository.delete(category);
    }

    @Test
    @DisplayName("민수가 모임을 만들고 철수가 코드로 들어와 운동을 끝내면 — 민수 피드에 글이 뜨고, 리액션이 철수에게 보이고, 현황·재촉·푸시·알림함이 이어진다")
    void journey() throws Exception {
        // ── 1. 모임 생성 → 코드 참여 (#1·#2) ─────────────────────────────────────────
        String created = mockMvc.perform(json(post("/groups"), minsu)
                        .content("{\"name\":\"아침 스쿼트\",\"description\":\"매일 7시\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviteCode").isString())
                .andReturn().getResponse().getContentAsString();
        long groupId = JsonPath.parse(created).read("$.id", Long.class);
        String inviteCode = JsonPath.parse(created).read("$.inviteCode", String.class);

        mockMvc.perform(json(post("/groups/join"), cheolsu)
                        .content("{\"inviteCode\":\"" + inviteCode.toLowerCase() + "\"}"))   // 소문자로 쳐도 들어간다
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(groupId));

        // 이음새 #2→피드: 가입이 남긴 MEMBER_JOINED 가 피드의 첫 글이다(자기 자신은 포함 안 되는 «친구» 와 달리 글은 보인다).
        feed(minsu, groupId)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type").value(GroupEventTypes.MEMBER_JOINED))
                .andExpect(jsonPath("$.items[0].payload").value("{\"memberId\":" + cheolsu.getId() + ",\"username\":\"철수\"}"));

        // 아직 아무도 운동 안 함 — 현황은 둘 다 «오늘 안 함», 친구 목록에서 서로가 보인다(#3·#4).
        mockMvc.perform(auth(get("/friends"), minsu))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].memberId").value(cheolsu.getId()))
                .andExpect(jsonPath("$[0].attendedToday").value(false))
                .andExpect(jsonPath("$[0].streak").value(0));

        // ── 2. 철수 세션 완료 — AI 콜백 시뮬레이션 → 아웃박스 → 자동 글 (#10) ──────────────
        Session session = sessionRepository.saveAndFlush(Session.builder().member(cheolsu).exercise(squat)
                .referenceSource("https://youtu.be/dummy").startTime(LocalDateTime.now().minusMinutes(10))
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        @SuppressWarnings("unchecked")
        StreamObserver<SessionCompleteResponse> completeObs = mock(StreamObserver.class);
        grpcService.completeAnalysis(SessionCompleteRequest.newBuilder().setSessionId(session.getId())
                .setTotalReps(12).setAvgSyncRate(80).setMaxSyncRate(90).setMinSyncRate(70)
                .setCaloriesBurned(30.0).setDifficultyLevel(1).build(), completeObs);

        assertThat(sessionRepository.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(outboxRepository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getEventType()).isEqualTo(OutboxEventType.SESSION_COMPLETED));

        // 글은 발행기가 만든다 — tick 전엔 피드에 없다.
        feed(minsu, groupId).andExpect(jsonPath("$.items", hasSize(1)));
        publisher.dispatchPending();
        assertThat(outboxRepository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT));

        // 이음새 #10→#11: 자동 글의 payload(철수·스쿼트)가 민수 피드 최상단에 그대로.
        String feedJson = feed(minsu, groupId)
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].type").value(GroupEventTypes.SESSION_COMPLETED))
                .andExpect(jsonPath("$.items[0].senderId").value(cheolsu.getId()))
                .andExpect(jsonPath("$.items[0].reactionSummary.reactions.HEART").value(0))
                .andReturn().getResponse().getContentAsString();
        String payload = JsonPath.parse(feedJson).read("$.items[0].payload", String.class);
        assertThat(JsonPath.parse(payload).read("$.username", String.class)).isEqualTo("철수");
        assertThat(JsonPath.parse(payload).read("$.exerciseName", String.class)).isEqualTo("스쿼트");
        assertThat(JsonPath.parse(payload).read("$.sessionId", Long.class)).isEqualTo(session.getId());
        long postSeq = JsonPath.parse(feedJson).read("$.items[0].seq", Long.class);

        // ── 3. 민수가 리액션 → 철수 화면에 카운트로 보인다 (#11) ────────────────────────
        mockMvc.perform(auth(put("/groups/" + groupId + "/events/" + postSeq + "/reactions/HEART"), minsu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.HEART").value(1))
                .andExpect(jsonPath("$.myReactions[0]").value("HEART"));
        feed(cheolsu, groupId)
                .andExpect(jsonPath("$.items[0].reactionSummary.reactions.HEART").value(1))
                .andExpect(jsonPath("$.items[0].reactionSummary.myReactions", hasSize(0)));   // 남이 누른 것

        // ── 4. 이음새 #10→#3/#4: 완료가 곧 «오늘 출석» — 현황·친구 목록이 바뀐다 ─────────────
        mockMvc.perform(auth(get("/groups/" + groupId + "/members/status"), minsu))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].memberId").value(cheolsu.getId()))     // 오늘 완료가 앞
                .andExpect(jsonPath("$[0].attendedToday").value(true))
                .andExpect(jsonPath("$[0].streak").value(1))
                .andExpect(jsonPath("$[1].memberId").value(minsu.getId()))
                .andExpect(jsonPath("$[1].attendedToday").value(false));
        mockMvc.perform(auth(get("/friends"), cheolsu))
                .andExpect(jsonPath("$[0].memberId").value(minsu.getId()))
                .andExpect(jsonPath("$[0].attendedToday").value(false));

        // ── 5. 철수가 민수를 재촉 → 알림 행 + 푸시 아웃박스 → Expo → 민수 알림함 (#6·#8·#9) ────
        mockMvc.perform(json(post("/push-tokens"), minsu)
                        .content("{\"token\":\"ExponentPushToken[minsu-phone]\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isOk());
        when(expoPushClient.send(anyList())).thenReturn(List.of(new ExpoPushTicket("ok", "t1", null, null)));

        String nudged = mockMvc.perform(auth(post("/friends/" + minsu.getId() + "/nudge"), cheolsu))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("NUDGE"))
                .andExpect(jsonPath("$.senderId").value(cheolsu.getId()))
                .andReturn().getResponse().getContentAsString();
        long notificationId = JsonPath.parse(nudged).read("$.id", Long.class);

        assertThat(outboxRepository.findAll()).hasSize(2);   // SESSION_COMPLETED(SENT) + PUSH_NOTIFICATION(PENDING)
        publisher.dispatchPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpoPushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(expoPushClient).send(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("ExponentPushToken[minsu-phone]");
            assertThat(m.body()).isEqualTo("철수님이 오늘 운동을 재촉했어요");
        });
        assertThat(outboxRepository.findAll()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT));

        // 이음새 #6→알림함: 철수가 만든 행을 민수가 읽고, 읽음 처리까지.
        mockMvc.perform(auth(get("/notifications"), minsu))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(notificationId))
                .andExpect(jsonPath("$.content[0].senderUsername").value("철수"))
                .andExpect(jsonPath("$.content[0].read").value(false));
        mockMvc.perform(auth(patch("/notifications/" + notificationId + "/read"), minsu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        mockMvc.perform(auth(get("/notifications"), cheolsu))
                .andExpect(jsonPath("$.content", hasSize(0)));   // 보낸 사람 알림함엔 없다
    }

    @Nested
    @DisplayName("변형 — 탈퇴는 여러 기능에 한꺼번에 걸친다")
    class AfterLeave {

        @Test
        @DisplayName("철수가 나가면 — 민수 친구 목록에서 빠지고, 재촉 403, 철수는 피드 403, 남긴 글·리액션은 남는다")
        void leaveCutsAcrossFeatures() throws Exception {
            String created = mockMvc.perform(json(post("/groups"), minsu).content("{\"name\":\"g\"}"))
                    .andReturn().getResponse().getContentAsString();
            long groupId = JsonPath.parse(created).read("$.id", Long.class);
            String code = JsonPath.parse(created).read("$.inviteCode", String.class);
            mockMvc.perform(json(post("/groups/join"), cheolsu).content("{\"inviteCode\":\"" + code + "\"}"))
                    .andExpect(status().isCreated());
            long joinedSeq = JsonPath.parse(feed(minsu, groupId).andReturn().getResponse().getContentAsString())
                    .read("$.items[0].seq", Long.class);
            mockMvc.perform(auth(put("/groups/" + groupId + "/events/" + joinedSeq + "/reactions/FIRE"), cheolsu))
                    .andExpect(status().isOk());

            mockMvc.perform(auth(delete("/groups/" + groupId + "/members/me"), cheolsu)).andExpect(status().isOk());

            mockMvc.perform(auth(get("/friends"), minsu)).andExpect(jsonPath("$", hasSize(0)));
            mockMvc.perform(auth(post("/friends/" + cheolsu.getId() + "/nudge"), minsu)).andExpect(status().isForbidden());
            feed(cheolsu, groupId).andExpect(status().isForbidden());
            feed(minsu, groupId)
                    .andExpect(jsonPath("$.items[0].seq").value(joinedSeq))
                    .andExpect(jsonPath("$.items[0].reactionSummary.reactions.FIRE").value(1));
        }
    }

    // ---------------------------------------------------------------------

    private ResultActions feed(Member who, long groupId) throws Exception {
        return mockMvc.perform(auth(get("/groups/" + groupId + "/feed"), who));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req, Member who) {
        return req.header("Authorization", "Bearer " + tokenFor(who));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Member who) {
        return auth(req, who).contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder patch(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url);
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@journey.test").username(username)
                .password(passwordEncoder.encode("password123")).selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
