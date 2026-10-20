package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "피드 글 응원 req dto")
public class EventCheerRequestDto {
    @NotBlank
    @Size(max = 100)
    @Schema(description = "응원 문구 (1~100자, 앞뒤 공백은 서버가 지운다)", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "😆 다음에 같이 운동하자")
    private String message;
}
