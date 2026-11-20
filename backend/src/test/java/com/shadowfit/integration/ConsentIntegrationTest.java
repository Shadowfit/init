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
import com.shadowfit.service.member.MemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 약관·개인정보 동의 기록·조회 (V36). 서버의 동의 <b>강제는 꺼진 상태</b>(기본값) — 기록과 조회가 강제와 무관하게 동작하고,
 * 동의 필드를 모르는 현재 앱의 가입이 막히지 않는다는 호환 구간을 고정한다. 강제가 켜졌을 때는 {@link ConsentEnforcementIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("동의 기록·조회 (강제 꺼짐)")
class ConsentIntegrationTest {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberConsentRepository consentRepository;
    @Autowired private MemberService memberService;
    @Autowired private EntityManager em;

    private Member member;
    private String token;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("consent@test.com").username("consentuser").password("dummy").role(UserRole.USER).build());
        token = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build());
    }

    private ResultActions postConsents(List<ConsentItemDto> items) throws Exception {
        return mockMvc.perform(post("/member/consents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConsentRequestDto(items))));
    }

    private ResultActions getStatus() throws Exception {
        return mockMvc.perform(get("/member/consents").header("Authorization", "Bearer " + token));
    }

    private static ConsentItemDto item(ConsentType type, String version) {
        return new ConsentItemDto(type, version);
    }

    @Test
    @DisplayName("동의 전 현황 — 가입 필수 둘이 missing, 현재 버전과 강제 여부(꺼짐)를 알려 준다")
    void status_beforeAnyConsent() throws Exception {
        getStatus()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforced").value(false))
                .andExpect(jsonPath("$.current.TERMS").value("v1"))
                .andExpect(jsonPath("$.current.PRIVACY").value("v1"))
                .andExpect(jsonPath("$.current.HEALTH_INFO").value("v1"))
                .andExpect(jsonPath("$.agreed", empty()))
                .andExpect(jsonPath("$.missing", hasItems("TERMS", "PRIVACY")))
                // 건강정보는 가입 필수가 아니다 — 온보딩에서 몸 정보를 받기 직전에 따로 받는다.
                .andExpect(jsonPath("$.missing", hasSize(2)));
    }

    @Test
    @DisplayName("이용약관·개인정보에 현재 버전으로 동의하면 기록되고 missing 이 비워진다")
    void record_thenNothingMissing() throws Exception {
        postConsents(List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing", empty()))
                .andExpect(jsonPath("$.agreed", hasSize(2)));

        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(member.getId())).hasSize(2);
    }

    @Test
    @DisplayName("같은 동의를 다시 보내도 행이 늘지 않는다 (멱등)")
    void record_isIdempotent() throws Exception {
        List<ConsentItemDto> same = List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1"));

        postConsents(same).andExpect(status().isOk());
        postConsents(same).andExpect(status().isOk());

        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(member.getId())).hasSize(2);
    }

    @Test
    @DisplayName("현재 버전이 아닌 문서에 동의했다는 요청은 409(U006) — 옛 버전 기록을 만들지 않는다")
    void record_staleVersion_rejected() throws Exception {
        postConsents(List.of(item(ConsentType.TERMS, "v0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("개정")));

        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(member.getId())).isEmpty();
    }

    @Test
    @DisplayName("건강정보 동의는 가입 필수가 아니지만 기록할 수 있다")
    void record_healthInfo() throws Exception {
        postConsents(List.of(item(ConsentType.HEALTH_INFO, "v1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreed[0].type").value("HEALTH_INFO"));
    }

    @Test
    @DisplayName("빈 목록·알 수 없는 종류·버전 누락은 400")
    void record_badInput_400() throws Exception {
        postConsents(List.of()).andExpect(status().isBadRequest());
        postConsents(List.of(item(ConsentType.TERMS, ""))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/member/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consents\":[{\"type\":\"NOT_A_TYPE\",\"version\":\"v1\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토큰 없이는 조회도 기록도 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/member/consents")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/member/consents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"consents\":[]}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("남의 동의는 볼 수도 쓸 수도 없다 — 대상이 경로가 아니라 인증된 본인이다")
    void onlyOwnConsents() throws Exception {
        Member other = memberRepository.saveAndFlush(Member.builder()
                .email("other-consent@test.com").username("otherconsent").password("dummy").role(UserRole.USER).build());
        postConsents(List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1"))).andExpect(status().isOk());

        String otherToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(other.getEmail()).role(other.getRole()).build());
        mockMvc.perform(get("/member/consents").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreed", empty()));
    }

    @Test
    @DisplayName("[호환] 강제가 꺼져 있는 동안 동의 필드가 없는 가입은 그대로 통과하고 기록은 없다 — 현재 앱")
    void signupWithoutConsents_stillWorks_whileNotEnforced() throws Exception {
        mockMvc.perform(post("/member/signup")
                        .with(req -> { req.setRemoteAddr("10.55.0." + NEXT_IP.getAndIncrement()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MemberRequestDto("legacyapp", "legacy-app@test.com", "pw123456", Sex.MALE))))
                .andExpect(status().isOk());

        Member created = memberRepository.findByEmail("legacy-app@test.com").orElseThrow();
        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("동의를 담은 가입은 회원과 함께 기록된다 — 새 앱")
    void signupWithConsents_recorded() throws Exception {
        mockMvc.perform(post("/member/signup")
                        .with(req -> { req.setRemoteAddr("10.55.0." + NEXT_IP.getAndIncrement()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MemberRequestDto(
                                "newapp", "new-app@test.com", "pw123456", Sex.MALE,
                                List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1"))))))
                .andExpect(status().isOk());

        Member created = memberRepository.findByEmail("new-app@test.com").orElseThrow();
        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(created.getId())).hasSize(2);
    }

    @Test
    @DisplayName("탈퇴하면 동의 기록도 함께 지워진다 (FK CASCADE)")
    void withdrawal_deletesConsents() throws Exception {
        postConsents(List.of(item(ConsentType.TERMS, "v1"), item(ConsentType.PRIVACY, "v1"))).andExpect(status().isOk());
        Long memberId = member.getId();
        em.flush();
        em.clear();

        memberService.deleteAccount("consent@test.com");
        em.flush();
        em.clear();

        assertThat(consentRepository.findAllByMemberIdOrderByAgreedAtAsc(memberId)).isEmpty();
    }
}
