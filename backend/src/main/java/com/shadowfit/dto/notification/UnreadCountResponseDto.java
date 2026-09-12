package com.shadowfit.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "미읽음 알림 수 — 배지용")
public class UnreadCountResponseDto {
    private long unreadCount;
}
