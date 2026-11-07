package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 기준 좌표 직접 등록 — 관리자 웹이 3D 시범 마네킹의 관절을 «가상 랜드마크» 로 바꿔 보낸다(2026-10-20).
 *
 * <p>영상 업로드({@code POST /reference-video}) 경로는 AI 가 MediaPipe 로 좌표를 뽑지만, 마네킹을 영상으로 찍어
 * 다시 읽으면 투영 때문에 무릎이 20° 넘게 얕게 잡힌다. 이 경로는 좌표를 그대로 받아 {@code exercise_references} 를
 * 교체한다. 한 프레임의 {@code jointCoordinates} 는 AI 가 읽는 것과 같은 JSON 배열
 * ({@code [{index,x,y,z,visibility}, …, {index:-1, image_aspect_ratio, source}]}) 이다.
 */
@Schema(description = "기준 좌표 직접 등록 (3D 시범)")
public record AdminReferencePosesDto(
        @Schema(description = "출처 — 지금은 MANNEQUIN_3D 만 쓴다", example = "MANNEQUIN_3D")
        @NotBlank String source,

        @Schema(description = "프레임(시간순). 한 반복 분량, 4~2000개")
        @NotEmpty @Size(min = 4, max = 2000) @Valid List<Frame> frames
) {
    public record Frame(
            @Schema(description = "사이클 시작 기준 경과 초", example = "1.3")
            @NotNull @PositiveOrZero Double timestampSec,

            @Schema(description = "랜드마크 JSON 배열 문자열")
            @NotBlank String jointCoordinates
    ) {}
}
