package com.shadowfit.dto.group;

import com.shadowfit.model.group.ReactionKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 글 하나의 리액션 상태 — 피드 항목에 실리고, PUT/DELETE 리액션의 응답이기도 하다(§4-5 ④ — 프론트가 재조회
 * 없이 그린다). {@code reactions} 는 모든 종류의 키를 준다(0 포함, ⑨) — 프론트가 종류별 자리를 고정해 그리므로.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "리액션 요약 res dto")
public class ReactionSummaryDto {
    @Schema(description = "종류별 개수. 모든 종류의 키가 있다(0 포함)", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"HEART\": 3, \"FIRE\": 1}")
    private Map<ReactionKind, Long> reactions;

    @Schema(description = "요청자가 누른 종류", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ReactionKind> myReactions;
}
