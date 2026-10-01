package com.shadowfit.global.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.ErrorResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증이 없거나 무효인 요청의 401. 필터체인 단계라 {@code GlobalExceptionHandler} 를 안 타므로
 * 본문을 여기서 직접 쓴다 — 403({@link CustomAccessDeniedHandler})·429({@code AuthRateLimitFilter})와
 * 같은 {@link ErrorResponseDto} 모양이다.
 *
 * <p>예전엔 {@code response.sendError} 였다. 그러면 Spring 기본 {@code /error} 본문
 * ({@code error}·{@code path} 등)이 나가서 401 만 모양이 달랐다.
 *
 * <p>만료·위조·토큰 없음을 코드로 나누지 않고 A001 하나로 둔다 — 클라는 어떤 401 이든 먼저
 * 재발급을 시도하므로 대처가 같다(frontend/services/api.ts 응답 인터셉터). 원인은
 * {@code JwtAuthFilter} 의 WARN 로그에 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        // DEBUG 인 이유: access token 만료마다 한 번씩 오는 상시 경로다. 무효 토큰은 JwtAuthFilter 가
        // 이미 WARN 으로 남긴다. 예외 메시지는 싣지 않는다(JwtAuthFilter 와 같은 이유 — 토큰 조각).
        log.debug("Unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());

        ErrorCode code = ErrorCode.UNAUTHORIZED;
        response.setStatus(code.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponseDto.of(code)));
    }
}
