package com.shadowfit.controller;

import com.shadowfit.dto.consent.ConsentRequestDto;
import com.shadowfit.dto.consent.ConsentStatusDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.member.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 약관·개인정보·건강정보 동의 — 본인 것만 다룬다. 경로에 회원 식별자가 없다(인증된 본인이 곧 대상이라
 * 다른 회원의 동의를 읽고 쓰는 경로 자체가 없다).
 */
@Tag(name = "동의", description = "약관·개인정보·건강정보 동의 기록과 현황")
@RestController
@RequiredArgsConstructor
@RequestMapping("/member/consents")
public class ConsentController {

    private final ConsentService consentService;

    @Operation(summary = "동의 현황",
            description = "현재 문서 버전, 내 동의 이력, 현재 버전에 아직 동의하지 않은 가입 필수 문서(missing)를 돌려준다. "
                    + "앱은 missing 이 비어 있지 않으면 동의 화면을 띄운다 — 문서가 개정돼 버전이 오른 경우도 여기 잡힌다.")
    @GetMapping
    public ResponseEntity<ConsentStatusDto> status(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(consentService.status(userDetails.getMember().getId()));
    }

    @Operation(summary = "동의 기록",
            description = "동의한 문서를 기록한다. 같은 (종류, 버전)을 다시 보내도 행이 늘지 않는다(멱등). "
                    + "현재 버전이 아닌 값이면 409(U006)로 거절한다.")
    @PostMapping
    public ResponseEntity<ConsentStatusDto> record(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                   @Valid @RequestBody ConsentRequestDto request) {
        return ResponseEntity.ok(consentService.record(userDetails.getMember().getId(), request.getConsents()));
    }
}
