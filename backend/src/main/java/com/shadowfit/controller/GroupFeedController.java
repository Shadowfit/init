package com.shadowfit.controller;

import com.shadowfit.dto.group.GroupFeedResponseDto;
import com.shadowfit.dto.group.ReactionSummaryDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.group.ReactionKind;
import com.shadowfit.service.group.GroupFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 모임 피드 — 세션 완료 자동 글(#10)을 최신순으로 읽고 💗🔥 리액션을 단다(social-cheer-and-group-feed.md §4-5).
 * 백필 {@code GET /groups/{id}/events?afterSeq}(오름차순·전부, WS 봉투 겸용)와는 다른 API 다 — 백필은 놓친 것을
 * 채우는 일, 피드는 화면에 그리는 일.
 */
@Tag(name = "그룹", description = "모임 피드·리액션")
@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupFeedController {

    private final GroupFeedService groupFeedService;

    @Operation(summary = "모임 피드",
            description = "최신순 keyset. beforeSeq 를 생략하면 가장 최신부터. 응답의 nextBeforeSeq 를 다음 요청의 beforeSeq 로 넘긴다"
                    + "(null 이면 끝). size 기본 20·최대 100. 같은 그룹 ACTIVE 멤버만(403).")
    @GetMapping("/{groupId}/feed")
    public ResponseEntity<GroupFeedResponseDto> feed(
            @PathVariable Long groupId,
            @Parameter(description = "이 seq 미만부터 (생략 시 최신)") @RequestParam(required = false) Long beforeSeq,
            @Parameter(description = "페이지 크기 (최대 100)") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupFeedService.feed(groupId, userDetails.getMember().getId(), beforeSeq, size));
    }

    @Operation(summary = "리액션 달기(멱등)",
            description = "이미 있으면 그대로 200. 같은 글에 HEART·FIRE 둘 다 가능. kind 가 HEART|FIRE 밖이면 400, "
                    + "(groupId, seq) 의 글이 없으면 404, 같은 그룹 ACTIVE 멤버가 아니면 403. 응답은 갱신된 카운트·내 리액션.")
    @PutMapping("/{groupId}/events/{seq}/reactions/{kind}")
    public ResponseEntity<ReactionSummaryDto> react(
            @PathVariable Long groupId,
            @PathVariable Long seq,
            @PathVariable ReactionKind kind,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupFeedService.react(groupId, seq, userDetails.getMember().getId(), kind));
    }

    @Operation(summary = "리액션 취소(멱등)",
            description = "없어도 200. 응답은 갱신된 카운트·내 리액션.")
    @DeleteMapping("/{groupId}/events/{seq}/reactions/{kind}")
    public ResponseEntity<ReactionSummaryDto> unreact(
            @PathVariable Long groupId,
            @PathVariable Long seq,
            @PathVariable ReactionKind kind,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupFeedService.unreact(groupId, seq, userDetails.getMember().getId(), kind));
    }
}
