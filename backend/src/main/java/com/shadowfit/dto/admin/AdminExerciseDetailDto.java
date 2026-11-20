package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Exercise;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 관리자 운동 상세 — 등록·수정·상세조회의 공통 응답.
 *
 * <p>목록({@link AdminExerciseListItemDto})과 달리 <b>엔티티 전체</b>를 담는다. 상세는 한 건이라
 * 큰 컬럼을 읽어도 목록처럼 행 수만큼 곱해지지 않고, 관리자가 수정 폼을 채우려면 전부 필요하다.
 *
 * <p>판정 기준 한 쌍(정석·위험, V34)을 여기 포함하는 이유 — 값을 <b>바꾸는</b> 경로는
 * {@code PATCH /admin/exercises/{id}/thresholds} 로 따로 있지만, <b>보는</b> 경로가 상세뿐이다.
 * 수정 화면에서 현재값을 보여주려면 상세가 실어와야 한다.
 */
@Schema(description = "관리자 운동 상세")
public record AdminExerciseDetailDto(

        @Schema(description = "운동 ID", example = "1")
        Long id,

        @Schema(description = "운동명", example = "스쿼트")
        String name,

        @Schema(description = "종목 코드 — AI 분석기 키. null 이면 분석기가 없는 종목(분석을 켤 수 없다, W020)", example = "SQUAT")
        String code,

        @Schema(description = "부위 카테고리 ID")
        Long categoryId,

        @Schema(description = "부위 카테고리 이름")
        String categoryName,

        @Schema(description = "설명")
        String description,

        @Schema(description = "대표 영상 URL")
        String preferredUrl,

        @Schema(description = "기준 좌표를 뽑은 업로드 영상(저장 루트 기준 상대 경로). 업로드 이력이 없으면 null",
                example = "1/6f9a1c2e-….mp4")
        String referenceVideoPath,

        @Schema(description = "분석 대상 관절 (JSON 문자열)")
        String targetJoints,

        @Schema(description = "정석 기준 — 싱크로율이 이 점수 이상이면 정석", example = "80.0")
        Double syncGoodThreshold,

        @Schema(description = "위험 기준 — 이 점수 이상이면 교정 필요, 미만이면 위험", example = "60.0")
        Double syncWarnThreshold,

        @Schema(description = "예상 운동시간(분)", example = "15")
        Integer expectedDurationMinutes,

        @Schema(description = "AI 분석 지원 여부. 이 API 로는 바꿀 수 없다 — 클래스 주석 참고")
        Boolean analysisSupported,

        @Schema(description = "등록일시")
        LocalDateTime createdAt
) {
    public static AdminExerciseDetailDto fromEntity(Exercise e) {
        return new AdminExerciseDetailDto(
                e.getId(),
                e.getName(),
                e.getCode(),
                e.getCategory().getId(),
                e.getCategory().getName(),
                e.getDescription(),
                e.getPreferredUrl(),
                e.getReferenceVideoPath(),
                e.getTargetJoints(),
                e.getSyncGoodThreshold() == null ? null : e.getSyncGoodThreshold().doubleValue(),
                e.getSyncWarnThreshold() == null ? null : e.getSyncWarnThreshold().doubleValue(),
                e.getExpectedDurationMinutes(),
                e.getAnalysisSupported(),
                e.getCreatedAt()
        );
    }
}
