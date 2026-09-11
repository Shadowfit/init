package com.shadowfit.controller;

import com.shadowfit.dto.group.MemberAttendanceStatusDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.group.MemberAttendanceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
}
