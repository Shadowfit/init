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
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone().requestFactory(factory).build();
    }
}
