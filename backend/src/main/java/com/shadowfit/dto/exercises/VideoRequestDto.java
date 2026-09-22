package com.shadowfit.dto.exercises;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "운동세션 시작 req dto(앱->스프링)")
public class VideoRequestDto {
    /**
     * ⚠️ 2026-08-10 추가(이슈 #178): {@code @Schema(requiredMode = REQUIRED)} 는 <b>Swagger 문서에만</b>
     * 반영된다 — 런타임 검증과 무관하다. 즉 "필수" 라고 문서에 적혀 있는데 서버는 강제하지 않고 있었다.
     *
     * <p>없으면 404 가 아니라 <b>500</b> 이 난다. {@code ExercisesRepository:19} 의
     * {@code @Cacheable(key = "#id")} 가 <b>키 생성 단계에서</b> {@code IllegalArgumentException:
     * Null key returned for cache operation} 을 던지기 때문이다 — {@code WHERE e.id = :id} 쿼리는
     * 실행조차 되지 않아 "매치 0건 → Optional.empty() → 404" 경로에 도달하지 못한다.
     *
     * <p>🔴 <b>온보딩을 마친 정상 사용자만 맞았다.</b> {@code ExerciseAnalysisService.startAnalysis} 가
     * {@code preferredUrl} 을 먼저 검사해서, 온보딩 안 한 회원은 다른 이유로 400 을 받고 끝난다.
     * 정상적으로 쓰는 사용자일수록 5xx 를 받는 구조였다.
     */
    @NotNull(message = "운동 ID는 필수입니다.")
    @Schema(description = "운동 ID",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long exerciseId;

    /**
     * 세트당 목표 횟수(선택). 생략하면 {@code RecommendationService} 공식(페르소나·레벨)으로 채운다 —
     * 화면이 «추천값 미리 채우기» 를 하려면 {@code GET /recommendations/next-session} 의 {@code targetReps}.
     * 세트 경계는 «rep 이 이 값에 닿는 순간» 이라 세션 도중 바꿀 수 없다(lunge-and-set-backend.md §7).
     */
    @Min(value = 1, message = "세트당 목표 횟수는 1 이상이어야 합니다.")
    @Schema(description = "세트당 목표 횟수. 생략하면 추천 공식(페르소나·레벨)", example = "12")
    private Integer targetRepsPerSet;

    /** 목표 세트 수(선택). 세트 «수» 는 공식이 없어 사용자 값만 쓴다. 생략 = 열린 세트(끝낼 때까지). */
    @Min(value = 1, message = "목표 세트 수는 1 이상이어야 합니다.")
    @Schema(description = "목표 세트 수. 생략하면 열린 세트", example = "3")
    private Integer targetSets;

}
