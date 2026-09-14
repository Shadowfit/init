package com.shadowfit.service.report.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code llm.gemini.*} — 값의 근거는 application.yml 주석. */
@Component
@ConfigurationProperties(prefix = "llm.gemini")
@Getter
@Setter
public class GeminiProperties {

    /** 비어 있으면 LLM 을 안 부른다 — 주간 리포트는 전부 TEMPLATE_FALLBACK(reason=disabled). */
    private String apiKey = "";

    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /** 모델명은 설정값이다 — 2026-09-14 실측에서 2.5-flash-lite 가 404 로 사라져 있었다. */
    private String model = "gemini-3.5-flash-lite";

    private int timeoutSeconds = 10;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
