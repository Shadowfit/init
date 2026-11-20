package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.consent.ConsentItemDto;
import com.shadowfit.dto.consent.ConsentRequestDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.ConsentType;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberConsentRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의 <b>강제가 켜졌을 때</b>({@code consent.enforce=true}) — ① 가입은 이용약관·개인정보 현재 버전 동의가 있어야 하고
 * ② 키·몸무게를 보내는 온보딩은 건강정보 동의가 있어야 한다. 일반 동의와 건강정보 동의를 <b>분리</b>한다는 설계가 실제로 지켜지는지가
 * 이 테스트의 핵심이다 — 가입 동의만으로는 몸 정보를 저장할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "consent.enforce=true")
@DisplayName("동의 강제 (consent.enforce=true)")
class ConsentEnforcementIntegrationTest {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberConsentRepository consentRepository;

    private ResultActions signup(String username, String email, List<ConsentItemDto> consents) throws Exception {
        return mockMvc.perform(post("/member/signup")
                .with(req -> { req.setRemoteAddr("10.66.0." + NEXT_IP.getAndIncrement()); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new MemberRequestDto(username, email, "pw123456", Sex.MALE, consents))));
    }

    private static ConsentItemDto item(ConsentType type, String version) {
        return new ConsentItemDto(type, version);
    }

    @Test
    @DisplayName("동의 없이는 가입할 수 없다 — 400(U005), 회원이 만들어지지 않는다")
    void signup_withoutConsents_rejected() throws Exception {
        signup("noconsent", "no-consent@test.com", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("필수 약관")));

        assertThat(memberRepository.findByEmail("no-consent@test.com")).isEmpty();
    }

    @Test
    @DisplayName("이용약관만 동의하고 개인정보를 빠뜨려도 거절한다 — 둘 다 필요")
    void signup_missingOneRequired_rejected() throws Exception {
        signup("oneconsent", "one-consent@test.com", List.of(item(ConsentType.TERMS, "v1")))
                .andExpect(status().isBadRequest());

        assertThat(memberRepository.findByEmail("one-consent@test.com")).isEmpty();
    }

    @Test
    @DisplayName("옛 버전에 동의했다는 가입은 409(U006) — 개정된 문서를 읽지 않은 동의를 받지 않는다")
    void signup_staleVersion_rejected() throws Exception {
        signup("staleconsent", "stale-consent@test.com",
                List.of(item(ConsentType.TERMS, "v0"), item(ConsentType.PRIVACY, "v1")))
                .andExpect(status().isConflict());

        assertThat(memberRepository.findByEmail("stale-consent@test.com")).isEmpty();
    }

    @Test
    @DisplayName("두 문서의 현재 버전에 동의하면 가입되고 기록이 남는다")
    void signup_withBothConsents_ok() throws Exception {
        signup("okconsent", "ok-consent@test.com",
                List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1")))
                .andExpect(status().isOk());

        Member created = memberRepository.findByEmail("ok-consent@test.com").orElseThrow();
        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(created.getId())).hasSize(2);
    }

    @Test
    @DisplayName("건강정보 동의 없이 키·몸무게를 저장할 수 없고, 동의하면 저장된다 — 가입 동의와 분리")
    void onboarding_bodyInfo_requiresHealthInfoConsent() throws Exception {
        signup("bodyuser", "body-user@test.com",
                List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1")))
                .andExpect(status().isOk());
        Member member = memberRepository.findByEmail("body-user@test.com").orElseThrow();
        String token = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(member.getEmail()).role(UserRole.USER).build());

        // 가입 동의가 있어도 몸 정보는 안 된다
        mockMvc.perform(patch("/member/onboarding/" + member.getEmail())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"height\":175.0,\"weight\":70.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("건강정보")));
        assertThat(memberRepository.findByEmail(member.getEmail()).orElseThrow().getHeight()).isNull();

        // 몸 정보가 아닌 단계(페르소나·운동 수준)는 건강정보 동의 없이도 된다
        mockMvc.perform(patch("/member/onboarding/" + member.getEmail())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedPersona\":\"BEGINNER\"}"))
                .andExpect(status().isOk());

        // 건강정보에 동의하면 저장된다
        mockMvc.perform(post("/member/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentRequestDto(List.of(item(ConsentType.HEALTH_INFO, "v1"))))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/member/onboarding/" + member.getEmail())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"height\":175.0,\"weight\":70.0}"))
                .andExpect(status().isOk());
        assertThat(memberRepository.findByEmail(member.getEmail()).orElseThrow().getHeight()).isEqualTo(175.0);
    }
}
