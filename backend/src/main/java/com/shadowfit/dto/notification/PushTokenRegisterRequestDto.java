package com.shadowfit.dto.notification;

import com.shadowfit.model.notification.PushPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 푸시 토큰 등록 — 신규든 갱신이든 같은 요청. 토큰에 {@code [ ]} 가 있어 path 가 아니라 body 로 받는다.
 *
 * @param token    Expo 푸시 토큰. 형식이 아니면 400 — Expo 에 보내기 전에 걸러야 #9 의 실패 분류가 깨끗하다.
 *                 길이 상한은 컬럼(VARCHAR(255))과 같다.
 * @param platform 기기 플랫폼 — 발송엔 안 쓰이고 진단용
 */
@Schema(description = "푸시 토큰 등록 req dto")
public record PushTokenRegisterRequestDto(
        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^(ExponentPushToken|ExpoPushToken)\\[[^\\]]+\\]$",
                 message = "Expo 푸시 토큰 형식이 아닙니다.")
        @Schema(description = "ExponentPushToken[...] 또는 ExpoPushToken[...]", requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @NotNull
        @Schema(description = "IOS | ANDROID", requiredMode = Schema.RequiredMode.REQUIRED)
        PushPlatform platform
) {
}
