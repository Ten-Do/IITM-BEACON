package com.iitm.beacon.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

    @Test
    void trimsAndLowercases() {
        assertThat(EmailNormalizer.normalize("  Foo.Bar@Example.COM  "))
                .isEqualTo("foo.bar@example.com");
    }

    @Test
    void leavesAlreadyNormalizedEmailUnchanged() {
        assertThat(EmailNormalizer.normalize("foo@example.com")).isEqualTo("foo@example.com");
    }

    @Test
    void nullEmailThrows() {
        assertThatThrownBy(() -> EmailNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankEmailThrows() {
        assertThatThrownBy(() -> EmailNormalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyEmailThrows() {
        assertThatThrownBy(() -> EmailNormalizer.normalize(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
