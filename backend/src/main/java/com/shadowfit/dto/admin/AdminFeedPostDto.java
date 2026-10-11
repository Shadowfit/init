package com.shadowfit.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 — 피드 공유 글 한 줄 (사진 검수용)")
public record AdminFeedPostDto(
        @Schema(description = "group_events.id — 삭제 경로의 {postId}") Long postId,
        Long groupId,
        String groupName,
        Long seq,
        Long authorId,
        String authorUsername,
        String exerciseName,
        Integer totalReps,
        String caption,
        @Schema(description = "사진 경로 \"/feed-photos/…\" (없으면 null)") String photoUrl,
        long cheerCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt
) {}
