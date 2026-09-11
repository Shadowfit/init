package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 남에게 보이는 출석 상태 — social-cheer-and-group-feed.md §3-G 가 고정한 노출 항목(오늘 여부·연속일수)
 * 만 싣는다. 자세 점수·rep·칼로리 같은 건강 지표는 여기 들어오지 않는다.
 *
 * <p>화면 문구는 프론트가 파생한다: attendedToday → «N일째 운동 완료», 아니고 streak>0 → «N일째 운동 중»,
 * streak==0 → «최신 운동 기록이 없어요». 재촉 버튼 노출 = {@code !attendedToday}.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "구성원/친구 출석 상태 res dto")
public class MemberAttendanceStatusDto {
    @Schema(description = "회원 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long memberId;

    @Schema(description = "닉네임", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "프로필 이미지 URL (없으면 null)")
    private String profileImageUrl;

    @Schema(description = "오늘 COMPLETED 세션이 있는가", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean attendedToday;

    @Schema(description = "연속 출석 일수 (오늘 또는 어제까지 이어진 것, 없으면 0)", requiredMode = Schema.RequiredMode.REQUIRED)
    private int streak;
}
