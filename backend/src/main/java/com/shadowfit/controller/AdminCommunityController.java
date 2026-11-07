package com.shadowfit.controller;

import com.shadowfit.dto.admin.AdminFeedPostDto;
import com.shadowfit.dto.admin.AdminGroupDetailDto;
import com.shadowfit.dto.admin.AdminGroupListItemDto;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.service.admin.AdminInsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 — 모임과 피드 공유 글. 회원이 사진을 올리게 되면서(2026-10-06) 검수·삭제 경로가 필요해졌다.
 */
@Tag(name = "관리자 - 커뮤니티", description = "모임 · 피드 공유 글 관리 (운영자 전용)")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCommunityController {

    private final AdminInsightService adminInsightService;

    @Operation(summary = "모임 목록", description = "이름 검색(선택). 최신 생성순. 멤버 수 · 공유 글 수 · 마지막 활동 · 그룹장 포함.")
    @GetMapping("/groups")
    public ResponseEntity<PageResponse<AdminGroupListItemDto>> groups(
            @Parameter(description = "모임 이름 검색어") @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(adminInsightService.groups(keyword, page, size));
    }

    @Operation(summary = "모임 상세", description = "요약 + 멤버(탈퇴 포함). 없으면 404(G001).")
    @GetMapping("/groups/{groupId}")
    public ResponseEntity<AdminGroupDetailDto> group(@PathVariable Long groupId) {
        return ResponseEntity.ok(adminInsightService.groupDetail(groupId));
    }

    @Operation(summary = "모임 삭제", description = "멤버십 · 피드 · 응원 · 리액션을 함께 지우고 공유 사진 파일도 지운다. 되돌릴 수 없다.")
    @DeleteMapping("/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long groupId) {
        adminInsightService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "피드 공유 글 목록 (사진 검수)", description = "SESSION_SHARED 만, 최신순. groupId 로 좁힐 수 있다.")
    @GetMapping("/feed-posts")
    public ResponseEntity<PageResponse<AdminFeedPostDto>> feedPosts(
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(adminInsightService.feedPosts(groupId, page, size));
    }

    @Operation(summary = "피드 공유 글 삭제", description = "응원 · 리액션과 사진 파일도 지운다. 공유 글이 아니면 404(G009).")
    @DeleteMapping("/feed-posts/{postId}")
    public ResponseEntity<Void> deleteFeedPost(@PathVariable Long postId) {
        adminInsightService.deleteFeedPost(postId);
        return ResponseEntity.noContent().build();
    }
}
