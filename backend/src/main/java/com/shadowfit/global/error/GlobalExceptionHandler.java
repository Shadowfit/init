package com.shadowfit.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * BusinessException 의 ErrorCode 를 그대로 HTTP status 로 매핑 (BE-14/15 의 403/404 케이스 정합).
 * @Valid 실패·그 외 미처리 예외도 동일한 ErrorResponseDto 형식으로 통일 (api-improvement-opportunities.md §3-①).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponseDto> handleBusinessException(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("BusinessException: {} ({})", code.getCode(), code.getMessage());
        return ResponseEntity
                .status(code.getStatus())
                .body(ErrorResponseDto.builder()
                        .status(code.getStatus())
                        .message(code.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationException(MethodArgumentNotValidException e) {
        ErrorCode code = ErrorCode.INVALID_INPUT_VALUE;
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity
                .status(code.getStatus())
                .body(ErrorResponseDto.builder()
                        .status(code.getStatus())
                        .message(message.isEmpty() ? code.getMessage() : message)
                        .timestamp(LocalDateTime.now())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpectedException(Exception e) {
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        log.error("Unhandled exception", e);
        return ResponseEntity
                .status(code.getStatus())
                .body(ErrorResponseDto.builder()
                        .status(code.getStatus())
                        .message(code.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}
