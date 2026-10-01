package com.shadowfit.global.security.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터체인 단계 403 의 본문. 지금 SecurityConfig 에는 URL 단위 권한 규칙이 없어 통합 테스트로는
 * 이 핸들러에 닿기 어렵다(@PreAuthorize 403 은 GlobalExceptionHandler 로 간다) — 그래서 단위로 고정한다.
 * 두 자리가 같은 code(A002)를 내야 클라가 403 을 하나로 다룰 수 있다.
 */
@DisplayName("CustomAccessDeniedHandler — 403 본문")
class CustomAccessDeniedHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("403 + A002 + JSON")
    void writesCommonErrorBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CustomAccessDeniedHandler(objectMapper)
                .handle(new MockHttpServletRequest("GET", "/admin/x"), response, new AccessDeniedException("no"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("code").asText()).isEqualTo("A002");
    }
}
