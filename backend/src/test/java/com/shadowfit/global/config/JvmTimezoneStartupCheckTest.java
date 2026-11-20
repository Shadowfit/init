package com.shadowfit.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JVM 시간대 기동 검사 — 판정 (#846)")
class JvmTimezoneStartupCheckTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String URL = "jdbc:mysql://db:3306/shadowfit?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8";

    @Test
    @DisplayName("JVM 과 serverTimezone 이 같으면 통과한다")
    void sameZonePasses() {
        assertThatCode(() -> JvmTimezoneStartupCheck.check(SEOUL, URL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("JVM 이 UTC 인데 serverTimezone 이 Asia/Seoul 이면 거부한다 — CI 에서 난 조합")
    void differentZoneIsRejected() {
        assertThatThrownBy(() -> JvmTimezoneStartupCheck.check(UTC, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("#846");
    }

    @Test
    @DisplayName("이름이 달라도 규칙이 같은 시간대면 통과한다")
    void sameRulesPass() {
        assertThatCode(() -> JvmTimezoneStartupCheck.check(ZoneId.of("ROK"), URL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("connectionTimeZone 이 serverTimezone 보다 우선한다")
    void connectionTimeZoneWins() {
        String url = "jdbc:mysql://db/x?serverTimezone=Asia/Seoul&connectionTimeZone=UTC";
        assertThatCode(() -> JvmTimezoneStartupCheck.check(UTC, url)).doesNotThrowAnyException();
        assertThatThrownBy(() -> JvmTimezoneStartupCheck.check(SEOUL, url)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("URL 에 시간대가 없거나 LOCAL 이면 드라이버가 JVM 시간대를 써서 통과한다")
    void noZoneOrLocalPasses() {
        assertThatCode(() -> JvmTimezoneStartupCheck.check(UTC, "jdbc:mysql://db/x?useSSL=false")).doesNotThrowAnyException();
        assertThatCode(() -> JvmTimezoneStartupCheck.check(UTC, "jdbc:mysql://db/x?connectionTimeZone=LOCAL")).doesNotThrowAnyException();
    }
}
