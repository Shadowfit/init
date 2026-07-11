package com.shadowfit.dto.onboarding;

import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Data
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "온보딩 멤버 정보 수정 req dto")
public class OnboardingRequestDto {

    // 온보딩은 단계별로 나눠 부분 저장되므로(OnboardingService.updateOnboarding 참고) 이 넷은
    // 요청 1건 기준으로는 전부 선택값 — REQUIRED 표기는 실제 동작과 맞지 않아 제거함.
    @Schema(description = "목표 페르소나 설정", example = "ADVANCED")
    private SelectedPersona selectedPersona;

    @Schema(description = "나의 운동 레벨", example = "STARTER")
    private WorkoutLevel workoutLevel;

    @Schema(description = "키 (cm 단위)", example = "180.0")
    private Double height;

    @Schema(description = "몸무게 (kg 단위)", example = "75.5")
    private Double weight;

    @Schema(description = "선택한 스쿼트 기준 영상 URL", example = "https://www.youtube.com/watch?v=q6hBSSis_60")
    private String preferredUrl;
}
