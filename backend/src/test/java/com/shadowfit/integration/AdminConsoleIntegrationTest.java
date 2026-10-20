package com.shadowfit.integration;

import com.jayway.jsonpath.JsonPath;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 웹이 새로 쓰는 API — 회원 상세 · 모임 · 공유 글 검수 · 대시보드 추이/커뮤니티.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_console;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1",
        "feed-photo.dir=build/test-admin-feed-photos"
})
@DisplayName("관리자 콘솔 — 회원 상세 · 모임 · 공유 글 · 대시보드")
class AdminConsoleIntegrationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;

    private Member admin, jiwoo, minho;
    private Category category;
    private Exercise squat;

    @BeforeEach
    void setUp() {
        admin = save("콘솔관리자", UserRole.ADMIN);
        jiwoo = save("콘솔지우", UserRole.USER);
        minho = save("콘솔민호", UserRole.USER);
        category = categoryRepository.saveAndFlush(Category.builder().name("CONSOLE_LOWER").build());
        squat = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15).syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).analysisSupported(true).build());
    }

    @AfterEach
    void tearDown() {
        List<Long> ids = List.of(admin.getId(), jiwoo.getId(), minho.getId());
        for (Long id : ids) {
            jdbcTemplate.update("DELETE FROM workout_groups WHERE created_by = ?", id);
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id = ?", id);
        }
        memberRepository.deleteAll(List.of(admin, jiwoo, minho));
        exercisesRepository.delete(squat);
        categoryRepository.delete(category);
    }

    @Test
    @DisplayName("일반 회원은 새 관리자 API 전부 403")
    void nonAdminForbidden() throws Exception {
        for (String path : List.of("/admin/members/" + minho.getId(), "/admin/groups", "/admin/feed-posts",
                "/admin/stats/daily", "/admin/stats/community")) {
            mockMvc.perform(auth(get(path), jiwoo)).andExpect(status().isForbidden());
        }
        mockMvc.perform(auth(delete("/admin/groups/1"), jiwoo)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("회원 상세 · 모임 목록/상세 · 공유 글 검수/삭제 · 모임 삭제 · 대시보드 추이/커뮤니티")
    void consoleReads() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Session done = sessionRepository.saveAndFlush(Session.builder().member(jiwoo).exercise(squat)
                .referenceSource("https://youtu.be/dummy").startTime(start).endTime(start.plusMinutes(12))
                .status(Status.COMPLETED).totalReps(15).avgSyncRate(new BigDecimal("82.50")).difficultyLevel(1).build());

        // 모임 + 공유 글(사진)
        String g = mockMvc.perform(json(post("/groups"), jiwoo).content("{\"name\":\"새벽 스쿼트단\"}"))
                .andReturn().getResponse().getContentAsString();
        long groupId = JsonPath.parse(g).read("$.id", Long.class);
        mockMvc.perform(json(post("/groups/join"), minho)
                .content("{\"inviteCode\":\"" + JsonPath.parse(g).read("$.inviteCode", String.class) + "\"}"));
        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                        .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", JPEG))
                        .param("sessionId", String.valueOf(done.getId())).param("caption", "오늘도 완주"), jiwoo))
                .andExpect(status().isCreated());

        mockMvc.perform(auth(get("/admin/members/" + jiwoo.getId()), admin))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.sessionCount").value(1))
                .andExpect(jsonPath("$.completedSessionCount").value(1))
                .andExpect(jsonPath("$.averageSyncRate").value(82.5))
                .andExpect(jsonPath("$.groups[0].name").value("새벽 스쿼트단"))
                .andExpect(jsonPath("$.groups[0].role").value("OWNER"))
                .andExpect(jsonPath("$.recentSessions[0].exerciseName").value("스쿼트"));

        mockMvc.perform(auth(get("/admin/groups").param("keyword", "스쿼트"), admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ownerUsername").value("콘솔지우"))
                .andExpect(jsonPath("$.content[0].activeMemberCount").value(2))
                .andExpect(jsonPath("$.content[0].sharedPostCount").value(1));
        mockMvc.perform(auth(get("/admin/groups/" + groupId), admin))
                .andExpect(jsonPath("$.members", hasSize(2)));

        String posts = mockMvc.perform(auth(get("/admin/feed-posts"), admin))
                .andExpect(jsonPath("$.content[0].caption").value("오늘도 완주"))
                .andExpect(jsonPath("$.content[0].authorUsername").value("콘솔지우"))
                .andReturn().getResponse().getContentAsString();
        long postId = JsonPath.parse(posts).read("$.content[0].postId", Long.class);

        mockMvc.perform(auth(get("/admin/stats/daily").param("days", "7"), admin))
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[6].date").value(LocalDate.now().toString()));
        mockMvc.perform(auth(get("/admin/stats/community"), admin))
                .andExpect(jsonPath("$.sharedPostCount").value(1));

        mockMvc.perform(auth(delete("/admin/feed-posts/" + postId), admin)).andExpect(status().isNoContent());
        mockMvc.perform(auth(delete("/admin/feed-posts/" + postId), admin)).andExpect(status().isNotFound());
        mockMvc.perform(auth(delete("/admin/groups/" + groupId), admin)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/admin/groups/" + groupId), admin)).andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req, Member who) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(who.getEmail()).role(who.getRole()).build();
        return req.header("Authorization", "Bearer " + jwtUtil.createAccessToken(info));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Member who) {
        return auth(req, who).contentType(MediaType.APPLICATION_JSON);
    }

    private Member save(String username, UserRole role) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@console.test").username(username)
                .password(passwordEncoder.encode("password123")).selectedPersona(SelectedPersona.BEGINNER)
                .role(role).build());
    }
}
