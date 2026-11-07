package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 싱크로율 판정 기준 변경 — {@code PATCH /admin/exercises/{id}/thresholds} 본문 {@code {"good": 80, "warn": 60}}.
 *
 * <p>2026-10-21 «하나로 통일»(V34): 예전엔 페르소나별 4개(beginner/advanced/diet/rehab)를 받았지만 읽는 곳이 없었다.
 * 지금은 종목당 한 쌍이고 AI·앱으로 실제로 흐른다.
 *
 * <p>여기선 «값이 왔는가» 만 본다. {@code 0 < warn < good ≤ 100} 은 두 값의 관계라 서비스가 한 곳에서 검사하고
 * W022 로 답한다 — 필드별 {@code @DecimalMin} 으로 쪼개면 같은 불변식이 두 곳(C001·W022)으로 갈린다.
 */
@Schema(description = "싱크로율 판정 기준 변경")
public record ThresholdUpdateDto(
        @Schema(description = "정석 기준 — 이 점수 이상이면 정석", example = "80")
        @NotNull(message = "정석 기준(good)은 필수입니다")
        BigDecimal good,

        @Schema(description = "위험 기준 — 이 점수 이상이면 교정 필요, 미만이면 위험", example = "60")
        @NotNull(message = "위험 기준(warn)은 필수입니다")
        BigDecimal warn
) {
}