package com.shadowfit.service.notification.push;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Expo Push 메시지 한 건 — 요청 배열의 원소. 필드명은 Expo 규격 그대로라 Jackson 이 그대로 직렬화한다.
 *
 * @param to    ExponentPushToken[...]
 * @param data  프론트가 알림함으로 딥링크할 최소 정보({@code notificationId}, {@code type})
 * @param sound {@code "default"} — 없으면 iOS 에서 소리가 안 난다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpoPushMessage(String to, String title, String body, Map<String, Object> data, String sound) {

    public static ExpoPushMessage of(String to, String title, String body, Map<String, Object> data) {
        return new ExpoPushMessage(to, title, body, data, "default");
    }
}
