package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.Group;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 res dto")
public class GroupResponseDto {
    @Schema(description = "그룹 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "그룹 이름", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "모임 소개 한 줄 (선택)")
    private String description;

    @Schema(description = "코드 참여용 초대 코드 (8자리). 멤버가 공유한다", requiredMode = Schema.RequiredMode.REQUIRED)
    private String inviteCode;

    @Schema(description = "생성자 회원 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long createdById;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "생성 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    public static GroupResponseDto from(Group group) {
        return GroupResponseDto.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .inviteCode(group.getInviteCode())
                .createdById(group.getCreatedBy().getId())
                .createdAt(group.getCreatedAt())
                .build();
    }
}