package com.shadowfit.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401 이 다른 에러와 같은 {@code ErrorResponseDto} 모양으로 나가는지 검증한다.
 *
 * <p>예전 {@code CustomAuthenticationEntryPoint} 는 {@code response.sendError} 를 써서 Spring 기본
 * {@code /error} 본문({@code error}·{@code path})이 나갔다 — 403·429·비즈니스 에러와 401 만 모양이
 * 달랐다. {@code ProtectedEndpointSmokeTest} 는 status 만 보므로 이 회귀를 못 잡는다.
 *
 * <p>토큰이 없을 때와 위조 토큰일 때를 둘 다 본다 — 코드는 둘 다 A001 이다(만료·위조를 나누지 않는
 * 이유는 엔트리포인트 javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("401 응답 — 공통 에러 형식")
class UnauthenticatedErrorShapeTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없음 → 401 + A001, 기본 /error 본문의 필드는 없다")
    void noToken() throws Exception {
        mockMvc.perform(get("/reports/weekly-summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("A001"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    @DisplayName("위조 토큰 → 401 + A001")
    void forgedToken() throws Exception {
        mockMvc.perform(get("/reports/weekly-summary").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"));
    }
}
