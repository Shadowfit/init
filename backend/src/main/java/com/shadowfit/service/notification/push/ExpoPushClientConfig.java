package com.shadowfit.service.notification.push;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Expo 전용 {@link RestClient}. 타임아웃을 여기서 박는 이유 — Boot 의 기본 {@code RestClient.Builder}
 * 는 타임아웃이 무한이라, 그대로 쓰면 Expo 가 응답을 안 줄 때 발행기 스레드가 영원히 묶인다
 * (아웃박스 lease 60s 와의 관계는 {@link ExpoPushClient} 주석).
 */
@Configuration
public class ExpoPushClientConfig {

    @Bean
    RestClient expoRestClient(RestClient.Builder builder, ExpoPushProperties properties) {
        requireHttpsWhenTokenSet(properties);
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone().requestFactory(factory).build();
    }

    /**
     * 접근 토큰이 있는데 URL 이 HTTP 면 토큰이 평문으로 나간다 — 설정 실수를 런타임 유출이 아니라
     * 기동 실패로 바꾼다. 토큰 없는 HTTP(로컬 mock·테스트의 닫힌 포트)는 그대로 허용한다.
     */
    static void requireHttpsWhenTokenSet(ExpoPushProperties properties) {
        String token = properties.getAccessToken();
        String url = properties.getUrl();
        if (token != null && !token.isBlank() && (url == null || !url.regionMatches(true, 0, "https://", 0, 8))) {
            throw new IllegalStateException("push.expo.access-token 이 설정돼 있으면 push.expo.url 은 HTTPS 여야 한다: " + url);
        }
    }
}
