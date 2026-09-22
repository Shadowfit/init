package com.shadowfit.model.exercise;

/**
 * 종목 코드({@code exercises.code}) 값 규칙 — DTO 의 {@code @Pattern} 과 문서가 같은 문자열을 보게 한 곳으로 모은다.
 *
 * <p>대문자·숫자·밑줄, 첫 글자는 영문, 2~32자. ai-server 레지스트리 키가 소문자({@code "squat"})라도 여기서
 * 맞추지 않는다 — 변환은 경계(gRPC 송신부) 한 곳에서 하고, 저장값은 사람이 읽는 상수 꼴로 둔다.
 */
public final class ExerciseCode {
    public static final String PATTERN = "^[A-Z][A-Z0-9_]{1,31}$";
    public static final String PATTERN_MESSAGE = "종목 코드는 대문자·숫자·밑줄 2~32자이며 영문으로 시작해야 합니다";

    private ExerciseCode() {
    }
}
