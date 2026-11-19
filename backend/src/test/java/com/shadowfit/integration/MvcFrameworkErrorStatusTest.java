package com.shadowfit.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.GlobalExceptionHandler;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC 프레임워크가 던지는 «요청이 잘못됐다» 예외 셋이 500 이 아니라 제 상태코드로 나가는지 검증한다.
 *
 * <p>배경 — {@code GlobalExceptionHandler} 에 이 예외들의 핸들러가 없어 {@code @ExceptionHandler(Exception.class)}
 * 로 떨어졌고, 그 결과 <b>4xx 대신 500</b> + 매 요청 {@code log.error} 스택트레이스였다.
 * #129(404→500)·#180(400→500)과 <b>같은 형태</b>다. 이 테스트를 핸들러보다 먼저 써서 500 을 재현했다.
 *
 * <ul>
 *   <li>잘못된 HTTP 메서드 → {@code HttpRequestMethodNotSupportedException} → 405</li>
 *   <li>지원하지 않는 Content-Type → {@code HttpMediaTypeNotSupportedException} → 415</li>
 *   <li>필수 쿼리 파라미터 누락 → {@code MissingServletRequestParameterException} → 400</li>
 * </ul>
 *
 * <p>{@code /member/login} 을 쓰는 두 케이스는 whitelist 라 <b>인증 없이</b> 닿는다 — 즉 외부에서
 * ERROR 로그를 확정적으로 만들 수 있던 자리다(#180 과 같은 이유로 주 대상).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("MVC 프레임워크 예외 — 500 이 아니라 제 4xx")
class MvcFrameworkErrorStatusTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;

    private String token;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("mvc-framework-error@test.com").username("u").password("dummy")
                .role(UserRole.USER).build());
        token = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(member.getEmail()).role(member.getRole()).build());

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        handlerLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logCapture);
        logCapture.stop();
    }

    /**
     * PUT 을 쓰는 이유: DELETE 는 {@code DELETE /member/{email}} 에 {@code email="login"} 으로 <b>매칭돼</b>
     * 405 가 아니라 그 핸들러로 들어간다(별건 — whitelist 경로라 인증 없이 닿아 NPE→500).
     * /member 아래에는 PUT 매핑이 하나도 없어 순수하게 «메서드 불일치» 만 본다.
     */
    @Test
    @DisplayName("POST 전용 경로에 PUT → 405 + Allow 헤더 + C002")
    void wrongMethod_returns405() throws Exception {
        mockMvc.perform(put("/member/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("C002"));
        assertNoErrorLog();
    }

    @Test
    @DisplayName("JSON 을 받는 경로에 text/plain → 415 + C008")
    void unsupportedContentType_returns415() throws Exception {
        mockMvc.perform(post("/member/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("C008"));
        assertNoErrorLog();
    }

    @Test
    @DisplayName("필수 쿼리 파라미터 누락 → 400 + C001, 메시지에 파라미터 이름")
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/reports/daily").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("date")));
        assertNoErrorLog();
    }

    private void assertNoErrorLog() {
        assertThat(logCapture.list)
                .as("클라이언트 요청 오류는 서버 결함이 아니므로 ERROR 로 올리지 않는다")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }
}
