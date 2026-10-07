package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LogMask#email}: what the dev/test logging mailers write instead of
 * the recipient's address (NFR-CONTACT-CONFIDENTIALITY — an email is never
 * logged in plaintext, in any environment): the local part's first
 * character, {@code ***}, then {@code @} and the domain. Nothing else of the
 * local part — not even its length — is ever shown.
 */
class LogMaskTest {

    @Test
    void ordinaryAddress_keepsTheFirstCharacterAndTheDomain() {
        assertThat(LogMask.email("jane@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void localPartOfOneCharacter_isMaskedCompletely() {
        // Its first character would be the whole local part.
        assertThat(LogMask.email("j@example.com")).isEqualTo("***@example.com");
    }

    @Test
    void localPartOfTwoCharacters_keepsOnlyTheFirst() {
        assertThat(LogMask.email("jo@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void localPartsOfDifferentLengths_lookTheSame() {
        assertThat(LogMask.email("ab@example.com"))
                .isEqualTo(LogMask.email("abcdefghijklmnopqrstuvwxyz0123456789@example.com"))
                .isEqualTo("a***@example.com");
    }

    @Test
    void emptyLocalPart_isMaskedCompletely() {
        assertThat(LogMask.email("@example.com")).isEqualTo("***@example.com");
    }

    @Test
    void emptyDomain_keepsTheMaskedLocalPartAndTheAt() {
        assertThat(LogMask.email("jane@")).isEqualTo("j***@");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-an-email", "jane.example.com"})
    void nothingRecognisableAsAnAddress_isMaskedCompletely(String value) {
        assertThat(LogMask.email(value)).isEqualTo("***");
    }

    @Test
    void severalAts_theDomainIsWhatFollowsTheLastOne() {
        // A quoted local part may itself contain '@'.
        assertThat(LogMask.email("\"jane@home\"@example.com")).isEqualTo("\"***@example.com");
    }

    @Test
    void surroundingWhitespace_isIgnored() {
        assertThat(LogMask.email("  jane@example.com \t")).isEqualTo("j***@example.com");
    }

    @Test
    void firstCharacterOutsideTheBmp_isKeptWhole() {
        assertThat(LogMask.email("😀jane@example.com")).isEqualTo("😀***@example.com");
    }

    @Test
    void localPartOfOneCharacterOutsideTheBmp_isMaskedCompletely() {
        assertThat(LogMask.email("😀@example.com")).isEqualTo("***@example.com");
    }

    /** No forged log line: a line break or other control character never reaches the log as is. */
    @Test
    void controlCharacters_areReplaced() {
        String masked = LogMask.email("\u0007j\nfake@example.com\r\nINFO forged line");

        assertThat(masked).isEqualTo("?***@example.com??INFO forged line");
        assertThat(masked).doesNotContain("\n", "\r", "\u0007");
    }

    @Test
    void theLocalPartBeyondItsFirstCharacter_neverAppears() {
        assertThat(LogMask.email("visitor@example.com")).doesNotContain("visitor", "isitor");
    }
}
