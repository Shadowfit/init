package com.shadowfit.service.notification.push;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Expo Push 연동 설정 (social-cheer-and-group-feed.md §4-3 ⑥·⑧).
 *
 * <p>값의 근거는 {@code application.yml} 의 {@code push.expo} 블록에 있다 — 이 클래스는 받기만 한다.
 */
@Component
@ConfigurationProperties(prefix = "push.expo")
@Getter
@Setter
public class ExpoPushProperties {

    /** Expo Push API. 테스트는 {@code MockRestServiceServer} 로 이 URL 을 가로챈다. */
    private String url = "https://exp.host/--/api/v2/push/send";

    /**
     * Expo 접근 토큰(선택). 비어 있으면 {@code Authorization} 헤더를 안 붙인다 — 없어도 보내지지만,
     * 있으면 프로젝트 ID 를 아는 남이 우리 앱 이름으로 못 보낸다.
     */
    private String accessToken = "";

    /** 연결·읽기 타임아웃(초). 유도는 application.yml 주석. */
    private int timeoutSeconds = 5;
}
