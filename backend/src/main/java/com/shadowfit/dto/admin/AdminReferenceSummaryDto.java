package com.shadowfit.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 종목에 등록된 기준 좌표의 요약 — 관리자 화면이 «무엇이 기준인가» 와 «등록이 어떻게 됐나» 를 보여주는 데 쓴다.
 *
 * <p>2026-10-21 결정 B(기준 좌표 등록을 관리자에게 투명하게): 영상 추출은 비동기라 예전엔 결과가 AI 로그에만 남았다.
 * {@code extraction*} 3필드가 가장 최근 등록 시도의 결과를, {@code minKneeDeg}·{@code depthVerdict} 가 지금 기준이
 * 얼마나 깊은지(= 채점이 후한지·깐깐한지)를 보여준다.
 */
@Schema(description = "기준 좌표 요약")
public record AdminReferenceSummaryDto(
        @Schema(description = "프레임 수. 0 이면 기준 없음") long frameCount,
        @Schema(description = "MANNEQUIN_3D(3D 시범 관절) · VIDEO(업로드 영상에서 AI 가 추출) · null(없음)") String source,
        @Schema(description = "첫 프레임부터 마지막 프레임까지(초). 없으면 null") Double durationSec,
        @Schema(description = "기준의 가장 깊은 무릎각(도) — 좌우 평균, 이미지 평면 2D(AI 와 같은 정의). 없으면 null", example = "88.4")
        Double minKneeDeg,
        @Schema(description = "기준의 가장 깊은 고관절각(도) — 좌우 평균. 없으면 null", example = "71.2")
        Double minHipDeg,
        @Schema(description = "기준 깊이 판정 — STRICT(너무 깊어 대부분 감점) · OK · LENIENT(얕은 스쿼트도 만점) · null(프레임 없음)")
        String depthVerdict,
        @Schema(description = "가장 최근 등록 시도 — NONE(시도 없음) · EXTRACTING(AI 추출 중) · READY · FAILED. "
                + "EXTRACTING 이 10분 넘게 그대로면 FAILED 로 보인다")
        String extractionStatus,
        @Schema(description = "FAILED 사유(관리자에게 그대로 보여줄 문장). 아니면 null")
        String extractionError,
        @Schema(description = "등록 상태가 바뀐 시각. 시도가 없으면 null", example = "2026-10-21T14:03:12")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime extractionAt
) {
    public static final String SOURCE_MANNEQUIN = "MANNEQUIN_3D";
    public static final String SOURCE_VIDEO = "VIDEO";

    public static final String VERDICT_STRICT = "STRICT";
    public static final String VERDICT_OK = "OK";
    public static final String VERDICT_LENIENT = "LENIENT";

    /** 등록 시도가 없었다 — {@code exercises.reference_extraction_status} 가 NULL. */
    public static final String EXTRACTION_NONE = "NONE";
}
