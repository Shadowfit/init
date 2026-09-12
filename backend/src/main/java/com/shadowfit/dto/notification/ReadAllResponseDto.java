package com.shadowfit.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "모두 읽음 처리 결과")
public class ReadAllResponseDto {
    @Schema(description = "이번 호출로 읽음이 된 건수")
    private int updatedCount;
}
