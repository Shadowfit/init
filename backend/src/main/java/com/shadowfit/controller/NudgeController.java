package com.shadowfit.controller;

import com.shadowfit.dto.notification.NotificationResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 재촉하기 — 레퍼런스 3번 화면의 «재촉하기» 버튼. 경로가 /members/{id} 아래인 이유는 대상이 회원이기
 * 때문이고(social-cheer-and-group-feed.md §4-1 #6), 저장·조회는 {@link NotificationController}.
 */
@Tag(name = "알림", description = "받은 알림 목록·읽음 (재촉하기 저장 원천)")
@RestController
@RequiredArgsConstructor
public class NudgeController {

    private final NotificationService notificationService;

    @Operation(summary = "재촉하기",
            description = "같은 모임(ACTIVE)의 멤버에게 오늘 1회. 같은 모임이 아니면 403(G002), 오늘 이미 보냈으면 409(N002), "
                    + "자기 자신은 400(N003). «오늘 이미 완료한 대상» 은 서버가 막지 않는다 — 버튼 노출은 프론트 규칙.")
    @PostMapping("/members/{memberId}/nudge")
    public ResponseEntity<NotificationResponseDto> nudge(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long memberId
    ) {
        NotificationResponseDto response = notificationService.nudge(
                userDetails.getMember().getId(), memberId, LocalDate.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
