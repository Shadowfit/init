package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "코드로 모임 참여 req dto")
public class JoinGroupRequestDto {
    @NotBlank
    @Schema(description = "초대 코드 (8자리). 대소문자·앞뒤 공백은 서버가 정규화한다", requiredMode = Schema.RequiredMode.REQUIRED)
    private String inviteCode;
}
