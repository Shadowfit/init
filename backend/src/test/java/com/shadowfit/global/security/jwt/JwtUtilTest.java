package com.shadowfit.global.security.jwt;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.model.member.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 단위테스트 — 토큰 생성/검증/만료/파싱. 보안 메커니즘 자체가 지금까지 무테스트였던
 * 영역이라 happy path뿐 아니라 변조·만료·형식오류 케이스까지 검증.
 */
@DisplayName("JwtUtil 테스트")
class JwtUtilTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hmac-sha256-1234567890";

    private JwtUtil jwtUtil;
    private CustomUserInfoDto userInfo;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 3600L, 604800L); // access 1시간, refresh 7일
        userInfo = CustomUserInfoDto.builder().email("test@test.com").role(UserRole.USER).build();
    }

    @Test
    @DisplayName("access token 생성 후 이메일 추출·검증 가능")
    void createAccessToken_andExtractEmail() {
        String token = jwtUtil.createAccessToken(userInfo);

        assertThat(token).isNotBlank();
        assertThat(jwtUtil.getUserEmail(token)).isEqualTo("test@test.com");
        assertThat(jwtUtil.isValidToken(token)).isTrue();
    }

    @Test
    @DisplayName("refresh token도 정상 생성·검증됨")
    void createRefreshToken_isValid() {
        String token = jwtUtil.createRefreshToken(userInfo, 3L);

        assertThat(jwtUtil.isValidToken(token)).isTrue();
        assertThat(jwtUtil.getUserEmail(token)).isEqualTo("test@test.com");
        // ver claim 은 유예 판정의 재료다 (이슈 #135). 서명 안에 들어가므로 클라가 못 바꾼다.
        assertThat(jwtUtil.getTokenVersion(token)).isEqualTo(3L);
        // access token 에는 안 붙는다 — 회전 대상이 아니라 비교할 상대가 없다. 0 으로 읽힌다.
        assertThat(jwtUtil.getTokenVersion(jwtUtil.createAccessToken(userInfo))).isZero();
    }

    @Test
    @DisplayName("만료된 토큰은 isValidToken false — 단 parseClaims 는 여전히 claim 을 반환한다")
    void expiredToken_isInvalid_butClaimsStillReadable() {
        JwtUtil expiredIssuer = new JwtUtil(SECRET, -10L, -10L); // 발급 즉시 만료
        String expiredToken = expiredIssuer.createAccessToken(userInfo);

        assertThat(jwtUtil.isValidToken(expiredToken)).isFalse();
        Claims claims = jwtUtil.parseClaims(expiredToken);
        assertThat(claims.getSubject()).isEqualTo("test@test.com");
    }

    @Test
    @DisplayName("서명이 변조된 토큰은 isValidToken false")
    void tamperedToken_isInvalid() {
        String token = jwtUtil.createAccessToken(userInfo);
        String tampered = token.substring(0, token.length() - 5) + "AAAAA";

        assertThat(jwtUtil.isValidToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("형식이 아예 잘못된 문자열은 예외 전파 없이 isValidToken false")
    void malformedString_isInvalid() {
        assertThat(jwtUtil.isValidToken("not-a-jwt-at-all")).isFalse();
    }

    @Test
    @DisplayName("설정한 만료시간(초)이 exp claim 에 그대로 반영된다")
    void configuredExpiry_landsInExpClaim() {
        // getExpiration() 을 검사하던 테스트였다. 그 메서드를 지웠으므로(#137) 같은 계약을
        // parseClaims 로 검사한다 — 확인하려는 것은 메서드가 아니라 **단위가 초라는 사실**이고,
        // 그건 여전히 유효하다(JwtUtil 은 plusSeconds 로 쓴다).
        String token = jwtUtil.createAccessToken(userInfo);
        long expiration = jwtUtil.parseClaims(token).getExpiration().getTime();

        long expectedMin = System.currentTimeMillis() + 3600L * 1000 - 5000;
        long expectedMax = System.currentTimeMillis() + 3600L * 1000 + 5000;
        assertThat(expiration).isBetween(expectedMin, expectedMax);
    }

    @Test
    @DisplayName("access 는 refresh 가 아니고, refresh 는 refresh 다 — 서명 키가 같아도 종류를 구분한다")
    void tokenType_isDistinguished() {
        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .email("type@test.com").role(com.shadowfit.model.member.UserRole.USER).build();

        assertThat(jwtUtil.isRefreshToken(jwtUtil.createAccessToken(info))).isFalse();
        assertThat(jwtUtil.isRefreshToken(jwtUtil.createRefreshToken(info, 1L))).isTrue();
    }

    @Test
    @DisplayName("typ 가 없던 시절의 토큰 — ver 가 있으면 refresh, 없으면 access 로 본다(배포 순간 전원 로그아웃 방지)")
    void tokenType_legacyTokensWithoutTyp() {
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Date exp = java.util.Date.from(java.time.Instant.now().plusSeconds(600));

        String legacyAccess = io.jsonwebtoken.Jwts.builder().subject("l@test.com").expiration(exp).signWith(key).compact();
        String legacyRefresh = io.jsonwebtoken.Jwts.builder().subject("l@test.com").claim("ver", 3L).expiration(exp).signWith(key).compact();

        assertThat(jwtUtil.isRefreshToken(legacyAccess)).isFalse();
        assertThat(jwtUtil.isRefreshToken(legacyRefresh)).isTrue();
    }

    @Test
    @DisplayName("깨진 문자열은 예외 없이 false")
    void tokenType_malformedIsNotRefresh() {
        assertThat(jwtUtil.isRefreshToken("not-a-jwt")).isFalse();
    }
}
