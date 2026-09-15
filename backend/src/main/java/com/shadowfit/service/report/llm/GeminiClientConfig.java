package com.shadowfit.service.report.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/** {@code ExpoPushClientConfig} 와 같은 꼴 — 타임아웃을 요청 팩토리에 박은 전용 RestClient. */
@Configuration
public class GeminiClientConfig {

    @Bean
    RestClient geminiRestClient(RestClient.Builder builder, GeminiProperties properties) {
        requireGoogleHost(properties);
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone().requestFactory(factory).build();
    }

    /**
     * 키가 헤더로 나가므로 base-url 이 다른 호스트를 가리키면 키가 거기로 간다 — 설정 한 줄로 유출되는 경로를 기동 시 막는다
     * ({@code ExpoPushClientConfig.requireHttpsWhenTokenSet} 와 같은 판단). 키가 없으면 검사 안 함(어차피 안 부른다).
     */
    static void requireGoogleHost(GeminiProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(properties.getBaseUrl());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("llm.gemini.base-url 이 URL 이 아니다: " + properties.getBaseUrl(), e);
        }
        String host = uri.getHost();
        boolean google = host != null && (host.equals("googleapis.com") || host.endsWith(".googleapis.com"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !google) {
            throw new IllegalStateException("llm.gemini.api-key 가 설정돼 있으면 llm.gemini.base-url 은 https://*.googleapis.com 이어야 한다: "
                    + properties.getBaseUrl());
        }
    }
}
