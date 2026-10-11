package com.shadowfit.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.member.WorkoutLevel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "관리자 — 회원 상세")
public record AdminMemberDetailDto(
        Long id,
        String username,
        String email,
        UserRole role,
        Sex sex,
        SelectedPersona selectedPersona,
        WorkoutLevel workoutLevel,
        Double height,
        Double weight,
        boolean onboardingCompleted,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @Schema(description = "전체 세션 수") long sessionCount,
        @Schema(description = "완료 세션 수") long completedSessionCount,
        @Schema(description = "완료 세션 평균 싱크로율 (없으면 null)") Double averageSyncRate,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastSessionAt,
        @Schema(description = "참여 중인 모임") List<GroupMembership> groups,
        @Schema(description = "최근 세션 10개 (최신순)") List<RecentSession> recentSessions
) {
    public record GroupMembership(Long groupId, String name, GroupRole role) {}

    public record RecentSession(
            Long id,
            String exerciseName,
            Status status,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime startTime,
            Integer totalReps,
            BigDecimal avgSyncRate
    ) {}
}
