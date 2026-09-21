package com.shadowfit.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "응원 보내기 req dto")
public class CheerRequestDto {
    @NotBlank
    @Size(max = 100)
    @Schema(description = "응원 문구 — 정형 칩 또는 직접 입력. 앞뒤 공백은 서버가 지운다 (1~100자)",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "오늘도 힘내!")
    private String message;
}
