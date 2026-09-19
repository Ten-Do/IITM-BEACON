package com.iitm.beacon.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailLookupHashServiceTest {

    private final EmailLookupHashService service =
            new EmailLookupHashService(new CryptoProperties("key", "pepper-one"));

    @Test
    void sameNormalizedEmail_calledTwice_producesSameHash() {
        String hash1 = service.hash("foo@bar.com");
        String hash2 = service.hash("foo@bar.com");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void differingCaseAndWhitespace_normalizesToIdenticalHash() {
        String hash1 = service.hash(" Foo@Bar.com ");
        String hash2 = service.hash("foo@bar.com");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void differentEmails_produceDifferentHashes() {
        String hash1 = service.hash("foo@bar.com");
        String hash2 = service.hash("baz@bar.com");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void nullEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.hash("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.hash("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void output_isExactly64LowercaseHexCharacters() {
        String hash = service.hash("someone@example.com");

        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void sameEmail_withDifferentPepper_producesDifferentHash() {
        EmailLookupHashService otherPepperService =
                new EmailLookupHashService(new CryptoProperties("key", "pepper-two"));

        String hash1 = service.hash("someone@example.com");
        String hash2 = otherPepperService.hash("someone@example.com");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
