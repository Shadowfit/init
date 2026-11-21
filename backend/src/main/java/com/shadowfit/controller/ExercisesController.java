package com.shadowfit.controller;

import com.shadowfit.dto.exercises.session.ExercisesResponseDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.dto.exercises.ExerciseCatalogItemDto;
import com.shadowfit.service.exercise.ExerciseAnalysisService;
import com.shadowfit.service.exercise.ExerciseCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Tag(name = "운동 분석", description = "운동 분석 및 세션 관리 API")
@Slf4j
@RestController
@RequestMapping("/exercises")
@RequiredArgsConstructor
public class ExercisesController {

    private final ExerciseAnalysisService analysisService;
    private final ExerciseCatalogService catalogService;

    /**
     * 회원용 종목 목록 — 종목 선택 화면. {@code analysisSupported=false} 인 종목도 내린다
     * ({@link ExerciseCatalogItemDto} 주석). 07-api-design.md 에는 오래전부터 적혀 있었지만 구현이 없었다.
     */
    @Operation(summary = "운동 종목 목록", description = "종목 선택 화면용. analysisSupported=false 인 종목은 세션을 시작할 수 없다(W007)")
    @GetMapping
    public ResponseEntity<List<ExerciseCatalogItemDto>> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    /**
     * ✅ 기준 좌표 추출 (관리자/등록용)
     */
    @Operation(summary="기준 좌표 추출 (AI 컨테이너 안 파일 경로)",
            description = "⚠️ 유튜브 등 http(s) URL 은 400(W023)으로 거절한다 — AI 가 URL 을 받지 않는다(다운로드 약관 미결정). "
                    + "파라미터 이름만 youtubeUrl 이고 값은 AI 컨테이너가 열 수 있는 파일 경로다. "
                    + "실사용 경로는 mp4 업로드 POST /admin/exercises/{id}/reference-video")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{exerciseId}/reference")
    public ResponseEntity<String> extractReference(
            @PathVariable Long exerciseId,
            @RequestParam String youtubeUrl
    ) {
        // 🔴 URL 이면 바로 거절한다 (#800). 예전에는 202 를 주고 비동기로 AI 에 넘겼는데, AI 가 http(s) 를 거부해(success=false)
        //    실패가 AI 로그에만 남았다 — 관리자는 «시작되었습니다» 를 받고 아무 일도 안 일어났다.
        if (isHttpUrl(youtubeUrl)) {
            log.warn("기준 좌표 추출 거절 — URL 은 지원하지 않는다 (exerciseId: {})", exerciseId);
            throw new BusinessException(ErrorCode.REFERENCE_URL_NOT_SUPPORTED);
        }

        log.info("기준 좌표 추출 요청 - exerciseId: {}", exerciseId);

        analysisService.extractReferencePoses(exerciseId, youtubeUrl);

        return ResponseEntity.accepted()
                .body("운동 ID [" + exerciseId + "]에 대한 기준 좌표 추출이 시작되었습니다.");
    }


    /** AI(exercise_servicer.py ExtractReferenceData)가 거부하는 것과 같은 기준 — http:// · https:// 로 시작(대소문자·앞 공백 무시). */
    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String v = value.stripLeading().toLowerCase(java.util.Locale.ROOT);
        return v.startsWith("http://") || v.startsWith("https://");
    }

    /**
     * ✅ 운동 세션 시작 (핵심 API)
     * App → Spring → gRPC → FastAPI 흐름 시작점
     */
    @Operation(summary="운동 세션 시작",description = "운동을 시작할 수 있음/ ai서버에서 특정 조건을 달성하면 운동 종료가됨")
    // ⚠️ 2026-08-10: @Valid 가 없어 exerciseId=null 이 500 을 냈다 (이슈 #178).
    // @RequestBody 12곳 중 여기 하나만 빠져 있었다. DTO 의 @NotNull 과 **둘 다** 있어야 한다 —
    // @Valid 만 붙이면 제약이 없어 아무것도 안 걸리고, @NotNull 만 붙이면 트리거가 없어 평가되지 않는다.
    @PostMapping("/sessions")
    public ResponseEntity<ExercisesResponseDto> startAnalysis(
            @Valid @RequestBody VideoRequestDto dto,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberId = userDetails.getMember().getId();

        log.info("운동 분석 요청 시작 - userId: {}, exerciseId: {}",
                memberId, dto.getExerciseId());

        // 서비스 호출 (내부에서 gRPC 호출까지 이어짐)
        var started = analysisService.startAnalysis(dto, memberId);

        // 응답 DTO 생성
        //
        // sessionNonce 는 이 응답으로만 클라에 나간다 (#187 안 (d)) — 이후 POST /pose 마다
        // 동봉해야 AI 가 «이 세션을 만든 클라» 로 인정한다. 앱이 죽어 이 응답을 잃으면
        // GET /exercises/sessions/active 로 다시 받는다(ActiveSessionResponseDto).
        ExercisesResponseDto response = ExercisesResponseDto.builder()
                .sessionId(started.sessionId())
                .exerciseId(dto.getExerciseId())
                // 🔴 저장된 값을 그대로 싣는다 (#467). 예전엔 여기서 LocalDateTime.now() 를
                //    **새로 읽었다** — 세션을 저장한 시각과 다른 now() 호출이라, 초 경계를
                //    넘으면 클라가 받는 시각이 DB 와 1초 갈렸다(실측: 응답 13:21:04 · DB 05).
                //    표시용 값이 아니라 pose_data 의 멱등 앵커라서(#188 · #392) 어긋나면 안 된다.
                .startTime(started.startTime())
                .status(Status.IN_PROGRESS)
                .sessionNonce(started.sessionNonce())
                .aiWorkerIndex(started.aiWorkerIndex())
                .targetRepsPerSet(started.targetRepsPerSet())
                .targetSets(started.targetSets())
                // AnalyzeRequest 로 AI 에 간 것과 같은 판정 기준(V34)
                .syncGoodThreshold(started.syncGoodThreshold())
                .syncWarnThreshold(started.syncWarnThreshold())
                .build();

        return ResponseEntity.accepted().body(response);
    }
}