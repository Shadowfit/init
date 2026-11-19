package com.shadowfit.controller;

import com.shadowfit.service.group.FeedPhotoStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 피드 공유 사진 내려주기 — 인증 없음(security.whitelist). 웹의 {@code <img>} 는 Authorization 헤더를 못 싣는다.
 * 이름이 UUID 라 추측으로 닿을 수 없고, 그 이름은 모임 피드 응답에만 실린다. 사진은 바뀌지 않으므로 길게 캐시한다.
 */
@Tag(name = "그룹", description = "모임 피드·리액션")
@RestController
@RequiredArgsConstructor
public class FeedPhotoController {

    private final FeedPhotoStorage feedPhotoStorage;

    @Operation(summary = "피드 사진", description = "공유 글 payload 의 photoUrl. 없거나 형식이 다른 이름이면 404.")
    @GetMapping("/feed-photos/{name}")
    public ResponseEntity<Resource> photo(@PathVariable String name) {
        return feedPhotoStorage.resolve(name)
                .<ResponseEntity<Resource>>map(p -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(FeedPhotoStorage.contentType(name)))
                        .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS))
                        .body(new FileSystemResource(p)))
                .orElse(ResponseEntity.notFound().build());
    }
}
