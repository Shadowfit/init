package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "그룹장 양도 req dto")
public class TransferOwnershipRequestDto {
    @NotNull
    @Schema(description = "새 그룹장이 될 회원 id — 이 모임의 ACTIVE 멤버여야 한다", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long memberId;
}
