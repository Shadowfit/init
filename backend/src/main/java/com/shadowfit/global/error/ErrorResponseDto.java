package com.shadowfit.global.error;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 모든 에러 응답의 공통 본문. MVC 안({@code GlobalExceptionHandler})과 밖(시큐리티 필터체인의
 * 401·403, IP 시도 제한 429)이 <b>같은 모양</b>을 내야 해서, 만드는 길을 {@link #of} 하나로 모은다.
 *
 * <p>{@code code} 가 클라이언트의 분기 키다. {@code message} 는 사람에게 보여줄 문장이라 바뀔 수
 * 있으므로 분기에 쓰지 않는다 — 예전엔 code 가 없어 프론트가 문구로 갈라야 했다
 * (A004 무효 토큰 vs A006 폐기된 refresh 가 같은 401 이었다).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "공통 에러 응답 dto")
public class ErrorResponseDto {
    @Schema(description = "HTTP 상태 코드", example = "400", requiredMode = Schema.RequiredMode.REQUIRED)
    private int status;

    @Schema(description = "에러 코드 — 클라이언트 분기용. ErrorCode 의 code 값", example = "C001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Schema(description = "에러 메시지", example = "잘못된 요청입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "에러 발생 시간", example = "2026-01-12T20:30:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime timestamp;

    public static ErrorResponseDto of(ErrorCode code) {
        return of(code, code.getMessage());
    }

    /** 문구만 상황에 맞게 바꿀 때(필드별 검증 메시지 등). status·code 는 ErrorCode 에서 온다. */
    public static ErrorResponseDto of(ErrorCode code, String message) {
        return new ErrorResponseDto(code.getStatus(), code.getCode(), message, LocalDateTime.now());
    }
}
