package com.shadowfit.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 남의 세션과 없는 세션이 <b>같은 답</b>을 받는지 검증한다
 * (decisions/resource-ownership-403-vs-404.md 후보 C — 개인 소유 리소스는 404).
 *
 * <p>예전엔 이 세 엔드포인트만 조회 후 비교라 남의 세션에 403 을 줬다 — id 순차 대입으로 «존재하는
 * 세션 id» 를 알아낼 수 있었다. 서비스 단위 테스트는 목(mock)이라 derived query
 * ({@code findByIdAndMemberId}·{@code existsByIdAndMemberId})가 실제로 소유자를 거르는지 못 본다 —
 * 그래서 실제 DB 로 HTTP 까지 태운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("남의 세션 — 없는 세션과 같은 404")
class SessionOwnershipNotFoundTest {

    private static final long MISSING_ID = 99_999_999L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SessionRepository sessionRepository;

    private Session othersSession;
    private String strangerToken;

    @BeforeEach
    void setUp() {
        Member owner = memberRepository.saveAndFlush(Member.builder()
                .email("owner-404@test.com").username("owner").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Member stranger = memberRepository.saveAndFlush(Member.builder()
                .email("stranger-404@test.com").username("stranger").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        othersSession = sessionRepository.saveAndFlush(Session.builder()
                .member(owner).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(10))
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        strangerToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(stranger.getEmail()).role(stranger.getRole()).build());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"end", "feedbacks", "feedback-summary"})
    @DisplayName("남의 세션 → 404, 없는 세션과 본문이 같다")
    void othersSession_sameAsMissing(String endpoint) throws Exception {
        String others = mockMvc.perform(request(endpoint, othersSession.getId()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(request(endpoint, MISSING_ID))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // timestamp 만 다르다 — 나머지가 같아야 응답으로 둘을 못 가른다.
        assertThat(withoutTimestamp(others)).isEqualTo(withoutTimestamp(missing));
    }

    @Test
    @DisplayName("남의 종료 요청은 세션을 건드리지 않는다")
    void othersEnd_doesNotTouchSession() throws Exception {
        mockMvc.perform(request("end", othersSession.getId())).andExpect(status().isNotFound());

        assertThat(sessionRepository.findById(othersSession.getId()).orElseThrow().getEndTime()).isNull();
    }

    private MockHttpServletRequestBuilder request(String endpoint, long sessionId) {
        String path = "/sessions/" + sessionId + "/" + endpoint;
        MockHttpServletRequestBuilder builder = endpoint.equals("end") ? patch(path) : get(path);
        return builder.header("Authorization", "Bearer " + strangerToken);
    }

    // timestamp 는 직렬화 형식(문자열/배열)이 설정에 따라 달라서 정규식 대신 JSON 으로 지운다.
    private JsonNode withoutTimestamp(String body) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(body);
        node.remove("timestamp");
        return node;
    }
}
