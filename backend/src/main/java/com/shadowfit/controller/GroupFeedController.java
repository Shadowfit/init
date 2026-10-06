package com.shadowfit.controller;

import com.shadowfit.dto.group.EventCheerDto;
import com.shadowfit.dto.group.EventCheerRequestDto;
import com.shadowfit.dto.group.GroupFeedResponseDto;
import com.shadowfit.dto.group.ReactionSummaryDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.group.ReactionKind;
import com.shadowfit.service.group.GroupFeedService;
import com.shadowfit.service.group.GroupShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
    private final GroupShareService groupShareService;

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

    @Operation(summary = "운동 공유 (사진·한마디 선택)",
            description = "끝낸 세션을 이 모임 피드에 SESSION_SHARED 글로 올린다. multipart — sessionId(필수), caption(선택, 200자), "
                    + "photo(선택, jpg·png·webp 10MB). 같은 운동을 같은 모임에 두 번 올리면 409(G012) — 다른 모임에는 올릴 수 있다. "
                    + "내 것이 아니거나 끝나지 않은 세션 404(G013), 사진 형식 400(G014), 모임 멤버가 아니면 403. "
                    + "공개 항목은 종목·유효 횟수·운동 시간뿐(싱크로율 비공개).")
    @PostMapping(value = "/{groupId}/shares", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GroupFeedResponseDto.Item> share(
            @PathVariable Long groupId,
            @RequestParam Long sessionId,
            @RequestParam(required = false) String caption,
            @RequestPart(required = false) MultipartFile photo,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                groupShareService.share(groupId, userDetails.getMember().getId(), sessionId, caption, photo));
    }

    @Operation(summary = "이 운동을 이미 공유한 내 모임",
            description = "공유 화면에서 그 모임을 «공유됨» 으로 잠그는 용도. 내 세션이 아니면 404(G013).")
    @GetMapping("/shares")
    public ResponseEntity<List<Long>> sharedGroups(
            @RequestParam Long sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupShareService.sharedGroupIds(userDetails.getMember().getId(), sessionId));
    }

    @Operation(summary = "피드 글에 응원 한마디 (멱등)",
            description = "한 글에 회원당 한 줄 — 이미 있으면 문구를 바꾼다. 응답은 그 글의 응원 전부(오래된 순). "
                    + "글 없음 404(G009), 모임 멤버가 아니면 403.")
    @PutMapping("/{groupId}/events/{seq}/cheers")
    public ResponseEntity<List<EventCheerDto>> cheer(
            @PathVariable Long groupId,
            @PathVariable Long seq,
            @Valid @RequestBody EventCheerRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupFeedService.cheer(groupId, seq, userDetails.getMember().getId(), request.getMessage()));
    }

    @Operation(summary = "내 응원 지우기 (멱등)", description = "없어도 200. 응답은 그 글의 응원 전부.")
    @DeleteMapping("/{groupId}/events/{seq}/cheers")
    public ResponseEntity<List<EventCheerDto>> uncheer(
            @PathVariable Long groupId,
            @PathVariable Long seq,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupFeedService.uncheer(groupId, seq, userDetails.getMember().getId()));
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
