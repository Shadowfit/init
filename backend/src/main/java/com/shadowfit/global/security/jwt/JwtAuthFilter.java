package com.shadowfit.global.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.ErrorResponseDto;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.member.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Bearer 토큰을 검증해 {@link SecurityContextHolder} 에 인증을 심고, 그 주체를 MDC 에 얹는다.
 *
 * <p>🔴 <b>여기서 토큰과 이메일을 로그에 찍지 않는다</b> (이슈 #411). 예전에는 요청마다
 * {@code Authorization} 헤더 원문(= JWT 전체)과 이메일을 {@code INFO} 로 찍었다. 그게 왜
 * 결함이냐면 — 이 프로젝트는 #137 에서 <b>블랙리스트를 없애는 대신</b> access 수명을 30분으로
 * 줄이는 선택을 했고, 그 선택의 전제가 <i>"남는 노출이 그 잔여 수명뿐"</i> 이다. 토큰이 로그에
 * 남으면 그 전제가 깨진다 — 로그를 읽을 수 있으면 남의 신원으로 요청할 수 있고,
 * <b>블랙리스트가 없으므로 막을 수단도 없다.</b>
 *
 * <p>대신 주체는 {@link CorrelationIds#withActor(Long)} 로 MDC 에 들어가고, 로그 패턴
 * {@code [cid|sessionId|actor]} 를 통해 <b>모든 줄</b>에 붙는다 — 이 필터가 한 줄 찍는 것보다
 * 덮는 면적이 넓고, 값이 {@code member_id} 라 이메일이 안 샌다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    /** DB 가 응답하지 않을 때 클라가 다시 시도하기까지 기다릴 초. 정확한 복구 시간을 아는 것이 아니라 «바로 재시도 폭주» 를 막는 값이다. */
    private static final String RETRY_AFTER_SECONDS = "5";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/ws")) {
            filterChain.doFilter(request, response);
            return;
        }

        Long actorId;
        try {
            actorId = authenticate(request.getHeader("Authorization"));
        } catch (AuthDependencyUnavailableException e) {
            // 🔴 DB 장애는 «토큰이 틀렸다» 가 아니다 (#848). 예전에는 여기도 null 로 떨어져 401 이 됐고,
            //    클라는 401 에 재발급·로그아웃을 하므로 DB 가 몇 초 흔들려도 사용자가 로그아웃됐다. 모니터링에서도
            //    5xx 가 아닌 4xx 로 집계돼 에러율 알림에 안 잡혔다. 체인을 이어가지 않고 여기서 503 으로 끊는다.
            respondUnavailable(response);
            return;
        }

        // try-with-resources 로 감싸는 이유: MDC 는 ThreadLocal 이고 톰캣 워커는 재사용된다.
        // 지우지 않으면 다음 요청이 이전 요청의 actor 를 달고 로그를 찍는다 — SecurityConfig 가
        // MODE_INHERITABLETHREADLOCAL 을 거부한 것과 같은 종류의 오염이다.
        // actorId 가 null 이면 withActor 가 키를 지우므로, 인증 없는 요청은 자연히 빈 값이다.
        try (CorrelationIds.Scope ignored = CorrelationIds.withActor(actorId)) {
            filterChain.doFilter(request, response);
        }
    }

    private void respondUnavailable(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.SERVICE_UNAVAILABLE;
        response.setStatus(code.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponseDto.of(code)));
    }

    /** 인증 자체는 판단하지 못했는데 그 이유가 «토큰» 이 아니라 «의존 시스템(DB)» 인 경우. */
    private static final class AuthDependencyUnavailableException extends RuntimeException {
        AuthDependencyUnavailableException() {
            super(null, null, false, false); // 스택 없음 — 로그는 던지는 쪽에서 남긴다
        }
    }

    /**
     * 토큰이 유효하면 SecurityContext 에 인증을 심고 <b>주체의 member_id</b> 를 돌려준다.
     * 인증이 안 되면 {@code null} — 이 필터는 거부하지 않는다. 거부는 시큐리티 체인의 몫이고,
     * 여기서 막으면 whitelist 경로(로그인·회원가입)가 토큰 없이 못 지나간다.
     */
    private Long authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);

        try {
            // 블랙리스트 대조가 여기 있었다 (이슈 #137, decisions/token-lifecycle.md ㄴ-4).
            // 없앤 이유: 로그아웃이 refresh 를 확실히 지워 **갱신 경로가 끊기고**, access 수명이
            // 30분이라 남는 노출이 그 잔여 수명뿐이기 때문이다. 저장소를 고르는 대신 저장할
            // 대상을 없앴다 — 재기동 부활도, 다중 인스턴스 동기화도 같이 사라진다.
            if (!jwtUtil.isValidToken(token)) {
                log.warn("유효하지 않은 토큰입니다.");
                return null;
            }

            // refresh 토큰(7일)은 재발급 엔드포인트에서만 쓴다. 서명 키가 같아 이 검사가 없으면
            // API 인증에도 통과해, 30분으로 줄여 둔 access 수명이 의미를 잃는다.
            if (jwtUtil.isRefreshToken(token)) {
                log.warn("refresh 토큰은 API 인증에 사용할 수 없습니다.");
                return null;
            }

            String userEmail = jwtUtil.getUserEmail(token);
            if (userEmail == null) {
                log.warn("토큰의 Subject(Email)가 비어있습니다.");
                return null;
            }

            UserDetails userDetails = customUserDetailsService.loadUserByUsername(userEmail);
            if (userDetails == null) {
                return null;
            }

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()));

            // 🔴 여기서 이메일을 로그에 찍지 않는다 (#411). 「누구였나」는 아래 반환값이
            //    MDC 로 들어가 모든 줄에 붙으므로, 이 자리에 한 줄 더 찍을 이유가 없다.
            return userDetails instanceof CustomUserDetails details
                    ? details.getMember().getId()
                    : null;

        } catch (DataAccessException | TransactionException e) {
            // 회원 조회 중 DB 장애(연결 실패·타임아웃·커넥션 풀 고갈). 토큰 문제가 아니므로 401 로 만들지 않는다.
            // 클래스명만 남긴다 — 메시지에 SQL·접속 정보가 섞일 수 있다.
            log.error("JWT 인증 중 DB 장애: {}", e.getClass().getSimpleName());
            throw new AuthDependencyUnavailableException();
        } catch (Exception e) {
            // 예외 메시지에 토큰이 섞여 나오지 않게 클래스명만 남긴다. JJWT 의 일부 예외는
            // 메시지에 토큰 조각을 담는다 — 그러면 위에서 지운 것이 여기로 되돌아온다.
            log.warn("JWT 인증 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
