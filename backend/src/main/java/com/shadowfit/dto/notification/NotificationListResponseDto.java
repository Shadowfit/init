package com.shadowfit.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * keyset 페이지 — offset 이 아니라 «마지막으로 본 id» 로 다음 장을 연다. {@code nextCursor} 를
 * 그대로 {@code ?before=} 에 넣으면 된다. 다음 장이 없으면 {@code hasNext=false}, {@code nextCursor=null}.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "알림 목록 res dto (keyset)")
public class NotificationListResponseDto {

    @Schema(description = "최신순", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NotificationResponseDto> items;

    @Schema(description = "다음 장이 있는가", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean hasNext;

    @Schema(description = "다음 장 요청의 before 값 — 없으면 null")
    private Long nextCursor;
}
