package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 시작·진행 중 세션 응답에 싱크로율 판정 기준 한 쌍(V34)이 실리는지 — 앱이 점수를 «정석/교정 필요/위험» 으로
 * 칠하는 근거이고, 같은 값이 AnalyzeRequest 로 AI 에도 간다(그쪽은 {@code SessionMetricsRecordingTest} 가 본다).
 *
 * <p>{@code @Transactional} 을 거는 이유는 {@code SessionStartBodyValidationTest} 와 같다 — MockMvc 가 같은 스레드라
 * 테스트 트랜잭션에 합류하고, 롤백으로 끝나 afterCommit 의 실제 gRPC 발사가 일어나지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("세션 시작 응답 — 판정 기준 포함")
class SessionStartResponseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;

    private String userToken;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("session-start-thresholds@test.com").username("기준테스트").password("dummy")
                .preferredUrl("https://youtu.be/dummy")
                .selectedPersona(SelectedPersona.DIET)
                .role(UserRole.USER).build());
        Category category = categoryRepository.saveAndFlush(Category.builder().name("THRESHOLD_LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true)
                .syncGoodThreshold(new BigDecimal("85.00")).syncWarnThreshold(new BigDecimal("55.50"))
                .build());
        userToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(member.getEmail()).role(member.getRole()).build());
    }

    @Test
    @DisplayName("POST /exercises/sessions 와 GET /sessions/active 가 종목의 good/warn 을 싣는다 — 페르소나와 무관")
    void startAndActiveCarryThresholds() throws Exception {
        mockMvc.perform(post("/exercises/sessions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exerciseId\": " + exercise.getId() + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.syncGoodThreshold").value(85.0))
                .andExpect(jsonPath("$.syncWarnThreshold").value(55.5));

        mockMvc.perform(get("/sessions/active").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncGoodThreshold").value(85.0))
                .andExpect(jsonPath("$.syncWarnThreshold").value(55.5));
    }
}
