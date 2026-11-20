package com.shadowfit.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * 문자열이 UTF-8 로 인코딩했을 때 {@code value} 바이트 이하여야 한다.
 *
 * <p>비밀번호에 쓴다. BCrypt 는 입력의 앞 <b>72바이트</b>만 해시에 반영한다 — 글자 수가 아니라 바이트라서,
 * 한글(글자당 3바이트)은 24자만 넘어도 뒤가 조용히 잘려 «뒷부분이 달라도 로그인되는» 비밀번호가 된다.
 * {@code @Size(max = 72)} 로는 이걸 못 막는다(글자 수 기준).
 * {@code null} 은 통과시킨다 — 필수 여부는 {@code @NotBlank} 가 맡는다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
public @interface MaxUtf8Bytes {

    String message() default "허용 길이를 넘었습니다.";

    int value();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {
        private int max;

        @Override
        public void initialize(MaxUtf8Bytes annotation) {
            this.max = annotation.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
