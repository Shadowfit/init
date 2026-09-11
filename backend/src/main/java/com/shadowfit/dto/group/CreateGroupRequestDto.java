package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 생성 req dto")
public class CreateGroupRequestDto {
    @NotBlank
    @Schema(description = "그룹 이름", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 255)
    @Schema(description = "모임 소개 한 줄 (선택)")
    private String description;

    /** 이름만 받는 생성자 — description 추가(V15) 이전 호출부·테스트와의 호환용. */
    public CreateGroupRequestDto(String name) {
        this(name, null);
    }
}