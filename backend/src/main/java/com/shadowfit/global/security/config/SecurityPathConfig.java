package com.shadowfit.global.security.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "security")
@Getter
@Setter
public class SecurityPathConfig {
    /** 모든 HTTP 메서드에 permitAll 인 경로. */
    private List<String> whitelist;
    /** POST(와 CORS 사전요청 OPTIONS)에만 permitAll 인 경로 — 로그인·가입·재발급 (#810). */
    private List<String> whitelistPost = List.of();

    public String[] getWhiteListArray(){
        return whitelist.toArray(new String[0]);
    }

    public String[] getWhitelistPostArray(){
        return whitelistPost.toArray(new String[0]);
    }
}
