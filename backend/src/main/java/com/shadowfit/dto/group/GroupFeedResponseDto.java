package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.GroupEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 모임 피드 한 페이지 (social-cheer-and-group-feed.md §4-5 ①·②·⑨) — 최신순 keyset. 백필
 * ({@link GroupEventResponseDto}, 오름차순·전부)과 일부러 다른 DTO 다: 그쪽은 WS 봉투를 겸하고, 이쪽은 리액션을
 * 싣는다. 항목 필드는 백필과 같은 이름을 쓴다 — 프론트가 소켓으로 받은 이벤트를 피드 위에 그대로 얹을 수 있게.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "모임 피드 res dto")
public class GroupFeedResponseDto {
    @Schema(description = "최신순 항목", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Item> items;

    @Schema(description = "다음 페이지의 beforeSeq — 이 페이지가 size 만큼 찼을 때 마지막 항목의 seq, 아니면 null")
    private Long nextBeforeSeq;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @Schema(description = "피드 항목")
    public static class Item {
        @Schema(description = "그룹 내 시퀀스 번호 — 리액션 경로의 {seq}", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long seq;

        @Schema(description = "그룹 id", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long groupId;

        @Schema(description = "이벤트 타입 (SESSION_COMPLETED, MEMBER_JOINED, …)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String type;

        @Schema(description = "발신자 회원 id (시스템 이벤트는 null)")
        private Long senderId;

        @Schema(description = "이벤트 페이로드(JSON 문자열)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String payload;

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "발생 시각", requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDateTime occurredAt;

        @Schema(description = "리액션 개수·내 리액션", requiredMode = Schema.RequiredMode.REQUIRED)
        private ReactionSummaryDto reactionSummary;

        public static Item of(GroupEvent event, ReactionSummaryDto summary) {
            return Item.builder()
                    .seq(event.getSeq())
                    .groupId(event.getGroup().getId())
                    .type(event.getEventType())
                    .senderId(event.getSender() != null ? event.getSender().getId() : null)
                    .payload(event.getPayload())
                    .occurredAt(event.getCreatedAt())
                    .reactionSummary(summary)
                    .build();
        }
    }
}
