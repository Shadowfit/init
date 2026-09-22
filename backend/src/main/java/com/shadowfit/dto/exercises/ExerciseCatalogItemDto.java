package com.shadowfit.dto.exercises;

import com.shadowfit.model.exercise.Exercise;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원용 종목 목록 한 줄 ({@code GET /exercises}) — 종목 선택 화면이 그리는 것.
 *
 * <p>관리자 목록({@code AdminExerciseListItemDto})과 다른 DTO 인 이유 — 임계값·등록일·기준 영상 경로는 회원에게
 * 보일 값이 아니고, 반대로 {@code description}·{@code preferredUrl} 은 선택 화면이 쓴다.
 *
 * <p>{@code analysisSupported=false} 인 종목도 내린다. 목록에서 빼면 «런지가 있다는 사실」 자체가 화면에서
 * 사라지고, 준비 중 표시를 할지 숨길지는 화면의 결정이다(lunge-and-set-backend.md §3-C). 그 종목으로 세션을
 * 시작하면 W007 이다.
 */
@Schema(description = "회원용 운동 종목 목록 항목")
public record ExerciseCatalogItemDto(

        @Schema(description = "운동 ID — POST /exercises/sessions 의 exerciseId", example = "1")
        Long id,

        @Schema(description = "종목 코드 — AI 분석기 키. null 이면 분석기가 없는 종목", example = "SQUAT")
        String code,

        @Schema(description = "운동명", example = "스쿼트")
        String name,

        @Schema(description = "부위 카테고리 ID")
        Long categoryId,

        @Schema(description = "부위 카테고리 이름", example = "LOWER")
        String categoryName,

        @Schema(description = "설명")
        String description,

        @Schema(description = "대표 영상 URL")
        String preferredUrl,

        @Schema(description = "예상 운동시간(분)", example = "15")
        Integer expectedDurationMinutes,

        @Schema(description = "AI 분석 지원 여부. false 면 세션을 시작할 수 없다(W007) — 화면은 «준비 중» 으로")
        Boolean analysisSupported
) {
    public static ExerciseCatalogItemDto fromEntity(Exercise e) {
        return new ExerciseCatalogItemDto(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getCategory().getId(),
                e.getCategory().getName(),
                e.getDescription(),
                e.getPreferredUrl(),
                e.getExpectedDurationMinutes(),
                e.getAnalysisSupported()
        );
    }
}
