package com.shadowfit.service.report.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** {@code ExpoPushClientConfig} 와 같은 꼴 — 타임아웃을 요청 팩토리에 박은 전용 RestClient. */
@Configuration
public class GeminiClientConfig {

    @Bean
    RestClient geminiRestClient(RestClient.Builder builder, GeminiProperties properties) {
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone().requestFactory(factory).build();
    }
}
