package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.model.member.Sex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원가입 입력 검증 — 길이·형식 한도. 이전에는 {@code @NotBlank} 뿐이라 이메일이 아닌 문자열, 컬럼 길이를 넘는
 * ID·이메일(DB 오류 → 500), BCrypt 가 조용히 잘라 버리는 72바이트 초과 비밀번호가 그대로 들어왔다.
 *
 * <p>인증 경로 IP 제한(분당 60회)이 컨텍스트를 공유하는 다른 테스트와 카운터를 나누므로, 요청마다 다른
 * 원격 주소를 쓴다 — 이 테스트가 다른 테스트를 429 로 밀어내지 않게.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("회원가입 입력 검증")
class SignupInputValidationTest {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private org.springframework.test.web.servlet.ResultActions signup(String username, String email, String password)
            throws Exception {
        String ip = "10.77.0." + NEXT_IP.getAndIncrement();
        return mockMvc.perform(post("/member/signup")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MemberRequestDto(username, email, password, Sex.MALE))));
    }

    @Test
    @DisplayName("경계값 — ID 3자, 비밀번호 6자, ASCII 72자, 한글 24자(72바이트)는 통과한다")
    void boundaries_accepted() throws Exception {
        signup("abc", "b1@test.com", "123456").andExpect(status().isOk());
        signup("u".repeat(50), "b2@test.com", "p".repeat(72)).andExpect(status().isOk());
        signup("korean24", "b3@test.com", "가".repeat(24)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ID 가 3자 미만이거나 컬럼 길이(50)를 넘으면 400")
    void username_length() throws Exception {
        signup("ab", "u1@test.com", "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ID는 3자 이상 50자 이하")));
        signup("u".repeat(51), "u2@test.com", "123456").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이메일 형식이 아니거나(점 없는 도메인 포함) 100자를 넘으면 400")
    void email_format_and_length() throws Exception {
        signup("emailuser1", "not-an-email", "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("올바른 이메일 형식이 아닙니다.")));
        signup("emailuser2", "a@b", "123456").andExpect(status().isBadRequest());
        signup("emailuser3", "a".repeat(95) + "@t.com", "123456").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비밀번호가 6자 미만이거나 72바이트를 넘으면 400 — 한글은 글자 수가 아니라 바이트 기준")
    void password_length_in_bytes() throws Exception {
        signup("pwuser1", "p1@test.com", "12345")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("6자 이상")));
        signup("pwuser2", "p2@test.com", "p".repeat(73)).andExpect(status().isBadRequest());
        signup("pwuser3", "p3@test.com", "가".repeat(25))   // 25자 = 75바이트 — @Size(max=72) 로는 못 잡는다
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("너무 깁니다")));
    }
}
