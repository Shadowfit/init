package com.shadowfit.controller;

import com.shadowfit.dto.notification.PushTokenRegisterRequestDto;
import com.shadowfit.dto.notification.PushTokenResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.notification.PushTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 푸시 디바이스 토큰 등록. 삭제 API 는 없다 — 로그아웃이 계정 단위로 지우고, 죽은 토큰은 발행기(#9)가
 * Expo 의 DeviceNotRegistered 를 보고 지운다(social-cheer-and-group-feed.md §4-2 ②).
 */
@Tag(name = "알림", description = "푸시 디바이스 토큰 등록")
@RestController
@RequestMapping("/push-tokens")
@RequiredArgsConstructor
public class PushTokenController {

    private final PushTokenService pushTokenService;

    @Operation(summary = "푸시 토큰 등록(멱등)",
            description = "앱 실행마다 호출. 신규든 갱신이든 200. 같은 토큰을 다른 계정이 등록하면 소유자가 옮겨간다 "
                    + "(한 기기의 토큰은 마지막으로 등록한 계정 것). Expo 토큰 형식이 아니면 400.")
    @PostMapping
    public ResponseEntity<PushTokenResponseDto> register(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PushTokenRegisterRequestDto request
    ) {
        return ResponseEntity.ok(pushTokenService.register(userDetails.getMember().getId(), request, LocalDateTime.now()));
    }
}
