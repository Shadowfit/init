package com.shadowfit.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 — 모임 목록 한 줄")
public record AdminGroupListItemDto(
        Long id,
        String name,
        String description,
        String inviteCode,
        @Schema(description = "그룹장 닉네임 (없으면 null)") String ownerUsername,
        @Schema(description = "ACTIVE 멤버 수") long activeMemberCount,
        @Schema(description = "공유 글(SESSION_SHARED) 수") long sharedPostCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @Schema(description = "마지막 피드 활동 시각 (없으면 null)")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastActivityAt
) {}
