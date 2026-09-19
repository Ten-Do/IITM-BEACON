package com.iitm.beacon.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests — {@link EncryptedValueConverter} is constructed directly
 * with a hand-built {@link CryptoProperties}, no Spring context needed.
 */
class EncryptedValueConverterTest {

    // Fixed 32-byte (AES-256) base64 key — same value used in dev/test config.
    private static final String VALID_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    private final EncryptedValueConverter converter =
            new EncryptedValueConverter(new CryptoProperties(VALID_KEY, "some-pepper"));

    @Test
    void roundTrip_returnsExactOriginalPlaintext() {
        String plaintext = "visitor@example.com";

        String stored = converter.convertToDatabaseColumn(plaintext);
        String recovered = converter.convertToEntityAttribute(stored);

        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void samePlaintextEncryptedTwice_producesDifferentCiphertextBothTimesButBothDecryptCorrectly() {
        String plaintext = "+91 98765 43210";

        String stored1 = converter.convertToDatabaseColumn(plaintext);
        String stored2 = converter.convertToDatabaseColumn(plaintext);

        assertThat(stored1).isNotEqualTo(stored2);
        assertThat(converter.convertToEntityAttribute(stored1)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(stored2)).isEqualTo(plaintext);
    }

    @Test
    void nullIn_producesNullOut_bothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void emptyString_roundTripsCorrectly() {
        String stored = converter.convertToDatabaseColumn("");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("");
    }

    @Test
    void tamperedCiphertext_throwsEncryptionException() {
        String stored = converter.convertToDatabaseColumn("sensitive-value");
        byte[] rawBytes = Base64.getDecoder().decode(stored);
        // Flip one byte well past the 12-byte IV, inside the ciphertext/tag.
        rawBytes[rawBytes.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(rawBytes);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void keyDecodingTo31Bytes_throwsIllegalStateExceptionAtConstruction() {
        String key31Bytes = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> new EncryptedValueConverter(new CryptoProperties(key31Bytes, "pepper")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyDecodingTo33Bytes_throwsIllegalStateExceptionAtConstruction() {
        String key33Bytes = Base64.getEncoder().encodeToString(new byte[33]);

        assertThatThrownBy(() -> new EncryptedValueConverter(new CryptoProperties(key33Bytes, "pepper")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonBase64Key_failsClearlyAtConstruction_notDeferredToFirstUse() {
        assertThatThrownBy(() -> new EncryptedValueConverter(new CryptoProperties("not-valid-base64-!!!", "pepper")))
                .isInstanceOf(IllegalStateException.class);
    }
}
