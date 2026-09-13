package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.notification.PushPlatform;
import com.shadowfit.model.notification.PushToken;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.PushTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 푸시 토큰 등록 upsert — social-cheer-and-group-feed.md §4-2. 토큰 전역 유일·소유자 이동·형식 검증·로그아웃 삭제.
 *
 * <p>클래스 {@code @Transactional} 을 <b>안 건다</b> — {@code PushTokenStore} 가 걸음마다 REQUIRES_NEW 로
 * 커밋하므로 테스트 트랜잭션 롤백에 안 묶인다. 대신 {@code @AfterEach} 로 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("푸시 토큰 등록 통합테스트")
class PushTokenIntegrationTest {

    private static final String TOKEN_A = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";
    private static final String TOKEN_B = "ExpoPushToken[bbbbbbbbbbbbbbbbbbbbbb]";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PushTokenRepository pushTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member me, other;

    @BeforeEach
    void setUp() {
        me = save("pt-me"); other = save("pt-other");
    }

    @AfterEach
    void tearDown() {
        pushTokenRepository.deleteAll();
        memberRepository.deleteAll(List.of(me, other));
    }

    @Test
    @DisplayName("첫 등록 200 — updatedAt 은 null(처음), 같은 계정이 다시 등록하면 같은 행에 updatedAt 만 찍히고 행은 1개")
    void register_thenReregister_sameRow() throws Exception {
        String id = mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN_A, "IOS")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(TOKEN_A))
                .andExpect(jsonPath("$.platform").value("IOS"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN_A, "IOS")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.parseLong(id)))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(pushTokenRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 토큰을 다른 계정이 등록하면 소유자가 옮겨간다 — 행은 여전히 1개, 이전 계정엔 0개")
    void register_sameTokenOtherMember_movesOwnership() throws Exception {
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN_A, "ANDROID")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN_A, "ANDROID")).header("Authorization", "Bearer " + tokenFor(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(pushTokenRepository.count()).isEqualTo(1);
        assertThat(pushTokenRepository.findAllByMemberId(me.getId())).isEmpty();
        assertThat(pushTokenRepository.findAllByMemberId(other.getId())).extracting(PushToken::getToken).containsExactly(TOKEN_A);
    }

    @Test
    @DisplayName("회원당 기기 여러 개 — 다른 토큰은 다른 행")
    void register_multipleDevices() throws Exception {
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN_A, "IOS")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN_B, "ANDROID")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk());

        assertThat(pushTokenRepository.findAllByMemberId(me.getId())).hasSize(2);
    }

    @Test
    @DisplayName("형식 검증 — Expo 토큰 꼴이 아니면 400, platform 없어도 400, 행 없음")
    void register_invalidInput() throws Exception {
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-a-token", "IOS")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(body("ExponentPushToken[]", "IOS")).header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN_A + "\"}").header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isBadRequest());

        assertThat(pushTokenRepository.count()).isZero();
    }

    @Test
    @DisplayName("로그아웃 — 그 계정의 푸시 토큰 전부 삭제(refresh token 과 같은 계정 단위), 남의 것은 그대로")
    void logout_deletesAllTokensOfMember() throws Exception {
        pushTokenRepository.saveAll(List.of(
                PushToken.builder().member(me).token(TOKEN_A).platform(PushPlatform.IOS).build(),
                PushToken.builder().member(me).token(TOKEN_B).platform(PushPlatform.ANDROID).build(),
                PushToken.builder().member(other).token("ExponentPushToken[cccccccccccccccccccccc]").platform(PushPlatform.IOS).build()));

        mockMvc.perform(post("/member/logout").header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isNoContent());

        assertThat(pushTokenRepository.findAllByMemberId(me.getId())).isEmpty();
        assertThat(pushTokenRepository.findAllByMemberId(other.getId())).hasSize(1);
    }

    private static String body(String token, String platform) {
        return "{\"token\":\"" + token + "\",\"platform\":\"" + platform + "\"}";
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
