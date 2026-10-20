package com.shadowfit.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "관리자 — 모임 상세")
public record AdminGroupDetailDto(
        AdminGroupListItemDto summary,
        @Schema(description = "멤버 (탈퇴 포함, 가입순)") List<Member> members
) {
    public record Member(
            Long memberId,
            String username,
            GroupRole role,
            GroupMemberStatus status,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime joinedAt
    ) {}
}
