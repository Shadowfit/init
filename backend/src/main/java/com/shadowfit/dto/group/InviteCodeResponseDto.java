package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "초대 코드 res dto")
public class InviteCodeResponseDto {
    @Schema(description = "코드 참여용 초대 코드 (8자리)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String inviteCode;
}
