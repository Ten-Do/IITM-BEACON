package com.iitm.beacon.common.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OtpCodeGeneratorTest {

    private final OtpCodeGenerator generator = new OtpCodeGenerator();

    @Test
    void generatesExactlySixCharacters() {
        String code = generator.generate();

        assertThat(code).hasSize(6);
    }

    @Test
    void onlyUsesAllowedAlphabetCharacters() {
        String code = generator.generate();

        assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
    }

    @Test
    void neverContainsAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            String code = generator.generate();
            assertThat(code).doesNotContain("0", "O", "1", "I", "L");
        }
    }

    @Test
    void manyConsecutiveCallsProduceUniqueCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(1000);
    }
}
