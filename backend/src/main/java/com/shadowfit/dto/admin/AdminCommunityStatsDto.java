package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 대시보드 — 커뮤니티 지표")
public record AdminCommunityStatsDto(
        @Schema(description = "모임 수") long groupCount,
        @Schema(description = "ACTIVE 모임 멤버십 수 (한 회원이 여러 모임이면 여러 번)") long activeMembershipCount,
        @Schema(description = "공유 글 수") long sharedPostCount,
        @Schema(description = "응원 한마디 수") long cheerCount,
        @Schema(description = "오늘 보낸 재촉 수") long todayNudgeCount
) {}
