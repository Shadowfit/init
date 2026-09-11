package com.shadowfit.controller;

import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.GroupDetailResponseDto;
import com.shadowfit.dto.group.GroupEventResponseDto;
import com.shadowfit.dto.group.GroupResponseDto;
import com.shadowfit.dto.group.InviteCodeResponseDto;
import com.shadowfit.dto.group.JoinGroupRequestDto;
import com.shadowfit.dto.group.MemberAttendanceStatusDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.service.group.GroupService;
import com.shadowfit.service.group.MemberAttendanceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "그룹(다중사용자 실시간 동기화)", description = "그룹 생성·조회·탈퇴, 실시간 이벤트 백필")
@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final GroupEventRepository groupEventRepository;
    private final MemberAttendanceStatusService memberAttendanceStatusService;

    @Operation(summary = "그룹 생성", description = "생성자가 OWNER로 자동 가입된다.")
    @PostMapping
    public ResponseEntity<GroupResponseDto> createGroup(
            @Valid @RequestBody CreateGroupRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        GroupResponseDto response = groupService.createGroup(userDetails.getMember().getId(), request);
        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "코드로 모임 참여",
            description = "초대 코드를 입력하면 승인 없이 바로 ACTIVE 멤버가 된다. 코드가 없으면 404, 이미 멤버면 409. "
                    + "참여하면 모임 사람들에게 출석 여부·연속일수가 보인다(social-cheer-and-group-feed.md §3-G).")
    @PostMapping("/join")
    public ResponseEntity<GroupResponseDto> joinByInviteCode(
            @Valid @RequestBody JoinGroupRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        GroupResponseDto response = groupService.joinByInviteCode(userDetails.getMember().getId(), request);
        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "내 그룹 목록", description = "내가 ACTIVE 멤버인 그룹만 반환한다.")
    @GetMapping("/mine")
    public ResponseEntity<List<GroupResponseDto>> listMyGroups(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupService.listMyGroups(userDetails.getMember().getId()));
    }

    @Operation(summary = "그룹 상세 조회", description = "멤버 목록 포함. 그룹 멤버가 아니면 403.")
    @GetMapping("/{groupId}")
    public ResponseEntity<GroupDetailResponseDto> getGroupDetail(
            @PathVariable Long groupId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupService.getGroupDetail(groupId, userDetails.getMember().getId()));
    }

    @Operation(summary = "구성원 운동 현황",
            description = "ACTIVE 멤버 전원의 오늘 완료 여부·연속일수. 오늘 완료 → 진행 중 → 기록 없음 순. "
                    + "그룹 멤버가 아니면 403. 노출 항목은 이 둘뿐(social-cheer-and-group-feed.md §3-G).")
    @GetMapping("/{groupId}/members/status")
    public ResponseEntity<List<MemberAttendanceStatusDto>> getMemberStatuses(
            @PathVariable Long groupId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(memberAttendanceStatusService.groupMemberStatuses(
                groupId, userDetails.getMember().getId(), LocalDate.now()));
    }

    @Operation(summary = "초대 코드 재발급",
            description = "코드 유출 시 그룹장이 새 코드로 갈아끼운다. 이전 코드는 즉시 무효. OWNER 가 아니면 403.")
    @PostMapping("/{groupId}/invite-code")
    public ResponseEntity<InviteCodeResponseDto> regenerateInviteCode(
            @PathVariable Long groupId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupService.regenerateInviteCode(groupId, userDetails.getMember().getId()));
    }

    @Operation(summary = "그룹 탈퇴")
    @DeleteMapping("/{groupId}/members/me")
    public ResponseEntity<Void> leaveGroup(
            @PathVariable Long groupId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        groupService.leaveGroup(groupId, userDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "재연결 백필",
            description = "WebSocket이 끊긴 동안 놓친 이벤트를 seq 오름차순으로 반환한다. afterSeq는 마지막으로 받은 seq.")
    @GetMapping("/{groupId}/events")
    public ResponseEntity<List<GroupEventResponseDto>> getEventsAfter(
            @PathVariable Long groupId,
            @RequestParam(defaultValue = "0") Long afterSeq,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        groupService.assertActiveMember(groupId, userDetails.getMember().getId());
        List<GroupEventResponseDto> events = groupEventRepository
                .findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(groupId, afterSeq).stream()
                .map(GroupEventResponseDto::from)
                .toList();
        return ResponseEntity.ok(events);
    }
}