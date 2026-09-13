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

/**
 * 알림함 한 줄. 보낸 사람 정보는 닉네임·프로필뿐이다 — §3-G 가 남에게 보이는 항목을 셋으로 고정했고
 * 알림은 그보다 적게 싣는다. 보낸 사람이 탈퇴하면 sender* 셋이 모두 null 이다(화면은 «탈퇴한 회원»).
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "알림 res dto")
public class NotificationDto {
    @Schema(description = "알림 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "종류", requiredMode = Schema.RequiredMode.REQUIRED)
    private NotificationType type;

    @Schema(description = "보낸 회원 id (탈퇴했으면 null)")
    private Long senderId;

    @Schema(description = "보낸 회원 닉네임 (탈퇴했으면 null)")
    private String senderUsername;

    @Schema(description = "보낸 회원 프로필 이미지 URL (없거나 탈퇴했으면 null)")
    private String senderProfileImageUrl;

    // 날짜 직렬화는 다른 group DTO 와 같이 필드에 명시한다 — 테스트 프로파일엔 write-dates-as-timestamps 설정이 없어
    // 애노테이션 없이는 [2026,9,12] 배열로 나간다.
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "하루 1회 판정 날짜 (서버 기준)", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate targetDate;

    @Schema(description = "읽었는가", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean read;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "읽은 시각 (안 읽었으면 null)")
    private LocalDateTime readAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "생성 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    public static NotificationDto from(Notification n) {
        Member sender = n.getSender();
        return NotificationDto.builder()
                .id(n.getId())
                .type(n.getType())
                .senderId(sender == null ? null : sender.getId())
                .senderUsername(sender == null ? null : sender.getUsername())
                .senderProfileImageUrl(sender == null ? null : sender.getProfileImageUrl())
                .targetDate(n.getTargetDate())
                .read(n.isRead())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
