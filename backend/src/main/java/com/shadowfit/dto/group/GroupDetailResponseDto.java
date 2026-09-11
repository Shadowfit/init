package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 상세 res dto")
public class GroupDetailResponseDto {
    @Schema(description = "그룹 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "그룹 이름", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "모임 소개 한 줄 (선택)")
    private String description;

    @Schema(description = "코드 참여용 초대 코드 (8자리). 멤버가 공유한다", requiredMode = Schema.RequiredMode.REQUIRED)
    private String inviteCode;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "생성 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @Schema(description = "멤버 목록", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<GroupMemberResponseDto> members;

    public static GroupDetailResponseDto from(Group group, List<GroupMember> members) {
        return GroupDetailResponseDto.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .inviteCode(group.getInviteCode())
                .createdAt(group.getCreatedAt())
                .members(members.stream().map(GroupMemberResponseDto::from).toList())
                .build();
    }
}