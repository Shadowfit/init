package com.shadowfit.global.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.ErrorResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

//로그인은 했는데 권한이 없을때
// 필터체인 단계의 403. @PreAuthorize 가 던지는 것은 MVC 안이라 GlobalExceptionHandler 가 받는다 —
// 두 자리가 같은 ErrorCode(A002)를 써야 클라가 «어떤 403 은 다르다» 를 배우지 않는다.
@Slf4j(topic ="Forbidden_EXCEPTION_HANDLER")
@AllArgsConstructor
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    @Override
    public void handle(final HttpServletRequest request, final HttpServletResponse response,
                       final AccessDeniedException accessDeniedException) throws IOException {
        // WARN·스택 없음 — 권한 부족은 서버 결함이 아니다(GlobalExceptionHandler 의 AccessDenied 와 같은 판단).
        log.warn("Access denied on {} {}", request.getMethod(), request.getRequestURI());

        ErrorCode code = ErrorCode.ACCESS_DENIED;
        response.setStatus(code.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponseDto.of(code)));
    }
}
