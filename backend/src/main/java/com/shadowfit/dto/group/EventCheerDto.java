package com.shadowfit.dto.group;

import com.shadowfit.model.group.EventCheer;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "피드 글 응원 한 줄")
public record EventCheerDto(
        @Schema(description = "응원한 회원 id", example = "3") Long memberId,
        @Schema(description = "응원한 회원 닉네임", example = "채린") String username,
        @Schema(description = "응원 문구", example = "😆 다음에 같이 운동하자") String message
) {
    public static EventCheerDto from(EventCheer c) {
        return new EventCheerDto(c.getMember().getId(), c.getMember().getUsername(), c.getMessage());
    }
}
