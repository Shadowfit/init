package com.shadowfit.dto.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.notification.PushPlatform;
import com.shadowfit.model.notification.PushToken;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "푸시 토큰 등록 res dto")
public record PushTokenResponseDto(
        Long id,
        String token,
        PushPlatform platform,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "처음 등록된 때")
        LocalDateTime createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "마지막으로 등록(갱신)된 때 — 첫 등록이면 null")
        LocalDateTime updatedAt
) {
    public static PushTokenResponseDto from(PushToken t) {
        return new PushTokenResponseDto(t.getId(), t.getToken(), t.getPlatform(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
