package com.shadowfit.service.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InviteCodeGenerator 테스트")
class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    @DisplayName("8자리이고, 혼동 글자(0/O/1/I)가 절대 안 나온다")
    void generate_isEightCharsFromAmbiguityFreeAlphabet() {
        for (int i = 0; i < 1_000; i++) {
            String code = generator.generate();
            assertThat(code).hasSize(InviteCodeGenerator.LENGTH);
            assertThat(code.chars().allMatch(c -> InviteCodeGenerator.ALPHABET.indexOf(c) >= 0))
                    .as("코드 %s 에 알파벳 밖 글자가 있다", code).isTrue();
            assertThat(code).doesNotContain("0", "O", "1", "I");
        }
    }

    @Test
    @DisplayName("연속 호출이 같은 값을 내지 않는다 (예측 불가 난수원)")
    void generate_doesNotRepeatAcrossCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }
}
