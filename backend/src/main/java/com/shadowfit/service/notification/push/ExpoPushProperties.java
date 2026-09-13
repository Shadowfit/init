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
     * Expo 접근 토큰(선택). 비어 있으면 {@code Authorization} 헤더를 안 붙인다 — 기본 발송은 수신자의
     * ExpoPushToken 만으로 된다. EAS 의 enhanced push security 를 켠 프로젝트는 이 토큰 없이는 발송이
     * 거절되므로 그때 채운다. 설정돼 있으면 {@link #url} 은 HTTPS 여야 한다({@code ExpoPushClientConfig}).
     */
    private String accessToken = "";

    /** 연결·읽기 타임아웃(초). 유도는 application.yml 주석. */
    private int timeoutSeconds = 5;
}
