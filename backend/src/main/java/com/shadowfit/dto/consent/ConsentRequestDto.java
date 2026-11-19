package com.shadowfit.dto.consent;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "동의 기록 요청")
public class ConsentRequestDto {

    @NotEmpty(message = "동의 항목이 비어 있습니다.")
    @Valid
    @Schema(description = "동의한 항목들")
    private List<ConsentItemDto> consents;
}
