package com.shadowfit.integration;

import com.jayway.jsonpath.JsonPath;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운동 공유 → 피드 → 응원 한마디. 공유는 사진 파일 저장과 글 INSERT 가 트랜잭션으로 묶이지 않으므로 클래스
 * {@code @Transactional} 없이 실제로 커밋시키고, 정리는 그룹 · 세션 · 회원 순으로 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:group_share;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1",
        "feed-photo.dir=build/test-feed-photos"
})
@DisplayName("운동 공유 · 피드 사진 · 응원 한마디")
class GroupShareIntegrationTest {

    // JPEG 매직 넘버(FF D8 FF) 로 시작하는 최소 바이트 — 형식 판정은 앞 바이트만 본다.
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;

    private Member minsu, cheolsu;
    private Category category;
    private Exercise squat;

    @BeforeEach
    void setUp() {
        minsu = save("공유민수");
        cheolsu = save("공유철수");
        category = categoryRepository.saveAndFlush(Category.builder().name("SHARE_LOWER").build());
        squat = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15).syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).analysisSupported(true).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        jdbcTemplate.update("DELETE FROM workout_groups WHERE created_by IN (?, ?)", minsu.getId(), cheolsu.getId());
        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id IN (?, ?)", minsu.getId(), cheolsu.getId());
        memberRepository.deleteAll(List.of(minsu, cheolsu));
        exercisesRepository.delete(squat);
        categoryRepository.delete(category);
        Path dir = Path.of("build/test-feed-photos");
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("사진과 함께 공유 → 피드에 SESSION_SHARED, 사진은 인증 없이 열림 · 같은 모임 재공유 409 · 응원은 회원당 한 줄")
    void shareThenCheer() throws Exception {
        long groupId = groupWithBoth();
        Session done = completedSession(cheolsu, 12);

        String created = mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                                .file(new MockMultipartFile("photo", "squat.jpg", "image/jpeg", JPEG))
                                .param("sessionId", String.valueOf(done.getId()))
                                .param("caption", "  오늘 스쿼트 12개 완료!  "), cheolsu))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value(GroupEventTypes.SESSION_SHARED))
                .andReturn().getResponse().getContentAsString();
        String payload = JsonPath.parse(created).read("$.payload", String.class);
        long seq = JsonPath.parse(created).read("$.seq", Long.class);
        String photoUrl = JsonPath.parse(payload).read("$.photoUrl", String.class);

        // 공개 항목은 종목·횟수·시간·한마디·사진 — 싱크로율은 없다
        org.assertj.core.api.Assertions.assertThat(JsonPath.parse(payload).read("$.exerciseName", String.class)).isEqualTo("스쿼트");
        org.assertj.core.api.Assertions.assertThat(JsonPath.parse(payload).read("$.totalReps", Integer.class)).isEqualTo(12);
        org.assertj.core.api.Assertions.assertThat(JsonPath.parse(payload).read("$.caption", String.class)).isEqualTo("오늘 스쿼트 12개 완료!");
        org.assertj.core.api.Assertions.assertThat(payload).doesNotContain("SyncRate");

        // 사진은 Authorization 없이 내려온다(웹 <img>)
        mockMvc.perform(get(photoUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
        mockMvc.perform(get("/feed-photos/..%2F..%2Fsecret.jpg")).andExpect(status().is4xxClientError());

        // 같은 운동을 같은 모임에 다시 → 409, 공유됨 목록에 이 모임
        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                                .param("sessionId", String.valueOf(done.getId())), cheolsu))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.SESSION_ALREADY_SHARED.getMessage()));
        mockMvc.perform(auth(get("/groups/shares").param("sessionId", String.valueOf(done.getId())), cheolsu))
                .andExpect(jsonPath("$", contains((int) groupId)));

        // 남의 세션은 공유 못 한다 — «없음» 과 같게 404
        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                                .param("sessionId", String.valueOf(done.getId())), minsu))
                .andExpect(status().isNotFound());

        // 응원 — 민수가 보내고, 다시 보내면 문구만 바뀐다(한 줄)
        mockMvc.perform(json(put("/groups/" + groupId + "/events/" + seq + "/cheers"), minsu)
                        .content("{\"message\":\"💗\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(json(put("/groups/" + groupId + "/events/" + seq + "/cheers"), minsu)
                        .content("{\"message\":\"😆 다음에 같이 운동하자\"}"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].username").value("공유민수"))
                .andExpect(jsonPath("$[0].message").value("😆 다음에 같이 운동하자"));

        // 피드 — 공유 글이 맨 위, 응원이 실려 온다
        mockMvc.perform(auth(get("/groups/" + groupId + "/feed"), cheolsu))
                .andExpect(jsonPath("$.items[0].type").value(GroupEventTypes.SESSION_SHARED))
                .andExpect(jsonPath("$.items[0].cheers", hasSize(1)))
                .andExpect(jsonPath("$.items[0].cheers[0].message").value("😆 다음에 같이 운동하자"))
                .andExpect(jsonPath("$.items[1].cheers", hasSize(0)));

        // 지우기는 멱등
        mockMvc.perform(auth(delete("/groups/" + groupId + "/events/" + seq + "/cheers"), minsu))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(auth(delete("/groups/" + groupId + "/events/" + seq + "/cheers"), minsu))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("사진이 아닌 파일 400 · 끝나지 않은 세션 404 · 사진 없이도 공유된다")
    void guards() throws Exception {
        long groupId = groupWithBoth();
        Session done = completedSession(cheolsu, 5);

        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                                .file(new MockMultipartFile("photo", "evil.jpg", "image/jpeg", "<script>".getBytes()))
                                .param("sessionId", String.valueOf(done.getId())), cheolsu))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_FEED_PHOTO.getMessage()));

        Session running = sessionRepository.saveAndFlush(Session.builder().member(cheolsu).exercise(squat)
                .referenceSource("https://youtu.be/dummy").startTime(LocalDateTime.now().minusMinutes(3))
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                                .param("sessionId", String.valueOf(running.getId())), cheolsu))
                .andExpect(status().isNotFound());

        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                                .param("sessionId", String.valueOf(done.getId())), cheolsu))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload", startsWith("{\"sessionId\":" + done.getId())));
    }

    private long groupWithBoth() throws Exception {
        String created = mockMvc.perform(json(post("/groups"), minsu).content("{\"name\":\"공유 모임\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.parse(created).read("$.inviteCode", String.class);
        mockMvc.perform(json(post("/groups/join"), cheolsu).content("{\"inviteCode\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
        return JsonPath.parse(created).read("$.id", Long.class);
    }

    private Session completedSession(Member who, int reps) {
        LocalDateTime start = LocalDateTime.now().minusMinutes(20);
        return sessionRepository.saveAndFlush(Session.builder().member(who).exercise(squat)
                .referenceSource("https://youtu.be/dummy").startTime(start).endTime(start.plusMinutes(15))
                .status(Status.COMPLETED).totalReps(reps).difficultyLevel(1).build());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req, Member who) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(who.getEmail()).role(who.getRole()).build();
        return req.header("Authorization", "Bearer " + jwtUtil.createAccessToken(info));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Member who) {
        return auth(req, who).contentType(MediaType.APPLICATION_JSON);
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@share.test").username(username)
                .password(passwordEncoder.encode("password123")).selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER).build());
    }
}
