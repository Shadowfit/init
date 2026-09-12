package com.shadowfit.dto.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "알림 1건 res dto")
public class NotificationResponseDto {

    @Schema(description = "알림 id — 목록 keyset 커서로 쓴다", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "종류", requiredMode = Schema.RequiredMode.REQUIRED)
    private NotificationType type;

    @Schema(description = "보낸 사람 — 탈퇴했으면 null")
    private SenderDto sender;

    @Schema(description = "재촉 대상 날짜(서버 기준). 같은 날 같은 사람에게는 1회", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate targetDate;

    @Schema(description = "읽은 시각 — 안 읽었으면 null")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readAt;

    @Schema(description = "생성 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @Schema(description = "알림 발신자")
    public static class SenderDto {
        private Long memberId;
        private String username;
        private String profileImageUrl;
    }

    public static NotificationResponseDto from(Notification n) {
        Member s = n.getSender();
        return NotificationResponseDto.builder()
                .id(n.getId())
                .type(n.getType())
                .sender(s == null ? null : SenderDto.builder()
                        .memberId(s.getId()).username(s.getUsername()).profileImageUrl(s.getProfileImageUrl()).build())
                .targetDate(n.getTargetDate())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
