package com.shadowfit.service.notification.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Expo 가 메시지 하나마다 돌려주는 티켓. {@code status} 는 {@code "ok"} 또는 {@code "error"} 이고,
 * 오류면 {@code details.error} 에 분류 코드가 실린다.
 *
 * <p>여기서 「ok」는 <b>Expo 가 받았다</b>는 뜻이지 폰이 울렸다는 뜻이 아니다 — 그건 receipt API 가
 * 따로 답하고, 이 프로젝트는 거기까지 안 본다(§3-C 하위 ①).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpoPushTicket(String status, String id, String message, Details details) {

    /** Expo 가 문서화한 {@code details.error} 값 중 이 발행기가 분기하는 것. */
    public static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";
    public static final String MESSAGE_RATE_EXCEEDED = "MessageRateExceeded";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(String error) {
    }

    public boolean ok() {
        return "ok".equals(status);
    }

    /** 오류 코드. 오류인데 코드가 없으면 {@code "unknown"} — 미지의 오류로 분류된다. */
    public String errorCode() {
        if (ok()) {
            return null;
        }
        return details == null || details.error() == null ? "unknown" : details.error();
    }
}
