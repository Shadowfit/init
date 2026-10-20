package com.shadowfit.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * «지금» 을 빈으로 — 시각에 의존하는 로직을 테스트가 고정 시각으로 돌릴 수 있게.
 *
 * <p>첫 사용처는 {@code PatternAnalysisService}(#739 — 세션을 넣고 조회하는 사이에 주 경계를 넘으면
 * 버킷이 밀려 실패하던 테스트). 프로젝트 나머지의 {@code LocalDateTime.now()} 직접 호출을 여기로
 * 모으는 건 #640 의 범위다 — 한 번에 바꾸지 않고 필요한 곳부터 옮긴다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
