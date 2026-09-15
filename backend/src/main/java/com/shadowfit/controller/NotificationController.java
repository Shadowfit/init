package com.shadowfit.controller;

import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.dto.notification.NotificationDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 내 알림함. 알림을 만드는 쪽은 각 기능의 컨트롤러다(재촉은 {@code POST /friends/{memberId}/nudge}) —
 * 여기는 «받은 것을 보고 읽음 처리» 만.
 *
 * <p>«모두 읽음» 은 없다 — 레퍼런스 화면에 그 버튼이 없어 근거 없는 API 가 된다(2026-09-12 사용자 confirm).
 */
@Tag(name = "알림", description = "받은 알림 조회·읽음 처리")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "내 알림 목록",
            description = "최신순. 보낸 사람이 탈퇴했으면 sender* 필드가 null. 페이지 크기는 최대 100.")
    @GetMapping
    public ResponseEntity<PageResponse<NotificationDto>> list(
            @Parameter(description = "페이지 번호 (0부터)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 100)")
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(notificationService.list(userDetails.getMember().getId(), page, size));
    }

    @Operation(summary = "알림 읽음 처리",
            description = "내 알림이 아니거나 없으면 404. 이미 읽은 알림은 처음 읽은 시각 그대로 200.")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<NotificationDto> markRead(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(notificationService.markRead(
                userDetails.getMember().getId(), notificationId, LocalDateTime.now()));
    }
}
