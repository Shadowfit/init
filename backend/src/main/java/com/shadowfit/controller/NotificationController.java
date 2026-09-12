package com.shadowfit.controller;

import com.shadowfit.dto.notification.NotificationListResponseDto;
import com.shadowfit.dto.notification.NotificationResponseDto;
import com.shadowfit.dto.notification.ReadAllResponseDto;
import com.shadowfit.dto.notification.UnreadCountResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
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
 * 받은 알림 — 목록(keyset)·미읽음 수·읽음 처리. 재촉을 «보내는» 쪽은 {@link NudgeController}
 * (대상이 회원이라 경로가 /members 아래다).
 */
@Tag(name = "알림", description = "받은 알림 목록·읽음 (재촉하기 저장 원천)")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "받은 알림 목록 (keyset)",
            description = "최신순. 첫 장은 before 없이, 다음 장은 응답의 nextCursor 를 before 에 넣는다. "
                    + "size 기본 20, 최대 50.")
    @GetMapping
    public ResponseEntity<NotificationListResponseDto> list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(notificationService.list(userDetails.getMember().getId(), before, size));
    }

    @Operation(summary = "미읽음 알림 수", description = "배지용 경량 조회 — 목록을 안 내려받고 수만 센다.")
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponseDto> unreadCount(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(new UnreadCountResponseDto(notificationService.unreadCount(userDetails.getMember().getId())));
    }

    @Operation(summary = "알림 1건 읽음", description = "본인 알림이 아니거나 없으면 404. 이미 읽은 건 그대로(멱등).")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<NotificationResponseDto> markRead(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long notificationId
    ) {
        return ResponseEntity.ok(notificationService.markRead(
                userDetails.getMember().getId(), notificationId, LocalDateTime.now()));
    }

    @Operation(summary = "모두 읽음", description = "안 읽은 알림 전부를 한 문장으로 읽음 처리. 바뀐 건수를 돌려준다.")
    @PatchMapping("/read-all")
    public ResponseEntity<ReadAllResponseDto> markAllRead(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(new ReadAllResponseDto(
                notificationService.markAllRead(userDetails.getMember().getId(), LocalDateTime.now())));
    }
}
