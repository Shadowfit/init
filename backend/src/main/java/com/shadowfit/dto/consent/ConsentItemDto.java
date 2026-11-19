package com.shadowfit.dto.consent;

import com.shadowfit.model.member.ConsentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 동의 한 건 — «이 종류의 문서를 이 버전으로 동의한다». */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "동의 한 건")
public class ConsentItemDto {

    @NotNull(message = "동의 종류는 필수입니다.")
    @Schema(description = "문서 종류", example = "TERMS", requiredMode = Schema.RequiredMode.REQUIRED)
    private ConsentType type;

    @NotBlank(message = "동의한 문서 버전은 필수입니다.")
    @Size(max = 20, message = "버전은 20자 이하여야 합니다.")
    @Schema(description = "동의한 문서 버전 — 현재 버전과 같아야 한다", example = "v1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String version;
}
