package com.shadowfit.controller;

import com.shadowfit.dto.group.MemberAttendanceStatusDto;
import com.shadowfit.dto.notification.CheerRequestDto;
import com.shadowfit.dto.notification.NotificationDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.group.MemberAttendanceStatusService;
import com.shadowfit.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 홈 «친구의 운동 현황». 친구 = 내가 속한 모임 멤버의 합집합(social-cheer-and-group-feed.md §3-A b) —
 * 경로가 /groups 가 아닌 이유는 화면 언어를 따르고, 나중에 친구 관계가 따로 생겨도 대상 집합만 바뀌게
 * 하려는 것(§3-A c).
 */
@Tag(name = "친구(모임 기반)", description = "내 모임 사람들의 운동 현황")
@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
public class FriendController {

    private final MemberAttendanceStatusService memberAttendanceStatusService;
    private final NotificationService notificationService;

    @Operation(summary = "친구의 운동 현황",
            description = "내가 속한 모든 모임의 ACTIVE 멤버(나 제외, 중복 제거)의 오늘 완료 여부·연속일수. "
                    + "오늘 완료 → 진행 중 → 기록 없음 순. 모임이 없으면 빈 목록.")
    @GetMapping
    public ResponseEntity<List<MemberAttendanceStatusDto>> getFriendStatuses(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(memberAttendanceStatusService.friendStatuses(
                userDetails.getMember().getId(), LocalDate.now()));
    }

    // 경로가 /friends 밑인 이유: 재촉할 수 있는 조건이 «같은 모임에 둘 다 ACTIVE» = 친구(§3-A b) 그 자체라
    // URL 이 하는 말과 권한 규칙이 같다. 결정 문서 §4-1 표기(/members/{id}/nudge)는 견적표의 표기였고
    // /member(단수)와 나란히 서는 걸 피했다(2026-09-12 사용자 confirm).
    @Operation(summary = "재촉하기",
            description = "같은 모임의 ACTIVE 멤버에게 재촉 알림을 남긴다(social-cheer-and-group-feed.md §3-C). "
                    + "같은 사람에게 하루 1회 — 두 번째는 409(N002). 같은 모임이 아니면 403, 없는 회원이면 404, "
                    + "자기 자신은 400. 상대가 오늘 이미 완료했는지는 서버가 보지 않는다(버튼 노출은 프론트). "
                    + "전달은 알림함(항상)·소켓(접속 중)·Expo 푸시(기기 등록 시).")
    @PostMapping("/{memberId}/nudge")
    public ResponseEntity<NotificationDto> nudge(
            @PathVariable Long memberId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                notificationService.nudge(userDetails.getMember().getId(), memberId, LocalDate.now()));
    }

    // 재촉과 별개 종류(CHEER)라 하루 1회 제한도 따로 센다 — 같은 사람에게 재촉 한 번 + 응원 한 번이 같은 날 가능하다.
    // 완료한 친구에게 보내는 것이 정상 경로라 attendedToday 로 버튼을 숨기지 않는다(재촉과 반대).
    @Operation(summary = "응원 보내기",
            description = "같은 모임의 ACTIVE 멤버에게 응원 문구(1~100자)를 남긴다. 같은 사람에게 하루 1회 — "
                    + "두 번째는 409(N004). 같은 모임이 아니면 403, 없는 회원이면 404, 자기 자신은 400(N005). "
                    + "전달 경로(알림함·소켓·푸시)는 재촉과 같다.")
    @PostMapping("/{memberId}/cheer")
    public ResponseEntity<NotificationDto> cheer(
            @PathVariable Long memberId,
            @Valid @RequestBody CheerRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                notificationService.cheer(userDetails.getMember().getId(), memberId, request.getMessage(), LocalDate.now()));
    }
}
