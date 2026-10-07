package com.iitm.beacon.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * NFR-CONTACT-CONFIDENTIALITY: a stored value is "unreadable without the
 * application's encryption key". Only the key it was written with decrypts
 * it — another key, or a value cut short, fails with {@link
 * EncryptionException} rather than yielding any plaintext. Pure unit tests,
 * like {@link EncryptedValueConverterTest}.
 */
class EncryptedValueConverterKeyTest {

    private static final String APP_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    /** Another valid AES-256 key: 32 bytes, differing from {@link #APP_KEY} in every byte. */
    private static final String OTHER_KEY = otherKey();

    private final EncryptedValueConverter appConverter = converterFor(APP_KEY);

    private static EncryptedValueConverter converterFor(String key) {
        return new EncryptedValueConverter(new CryptoProperties(key, "some-pepper"));
    }

    private static String otherKey() {
        byte[] bytes = Base64.getDecoder().decode(APP_KEY);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= (byte) 0xFF;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"visitor@example.com", "+91 98765 43210", ""})
    void valueWrittenWithTheAppKey_cannotBeDecryptedWithAnotherKey(String plaintext) {
        String stored = appConverter.convertToDatabaseColumn(plaintext);

        assertThatThrownBy(() -> converterFor(OTHER_KEY).convertToEntityAttribute(stored))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void keysDifferingInOneBitOnly_stillCannotDecryptEachOther() {
        byte[] bytes = Base64.getDecoder().decode(APP_KEY);
        bytes[31] ^= 0x01;
        String stored = appConverter.convertToDatabaseColumn("visitor@example.com");

        assertThatThrownBy(() -> converterFor(Base64.getEncoder().encodeToString(bytes))
                        .convertToEntityAttribute(stored))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void theSameKeyInASecondInstance_decrypts_soNothingDependsOnInstanceState() {
        String stored = appConverter.convertToDatabaseColumn("visitor@example.com");

        assertThat(converterFor(APP_KEY).convertToEntityAttribute(stored)).isEqualTo("visitor@example.com");
    }

    @Test
    void storedValue_isTheIvTheCiphertextAndTheTag_noShorter() {
        String plaintext = "visitor@example.com";

        byte[] decoded = Base64.getDecoder().decode(appConverter.convertToDatabaseColumn(plaintext));

        assertThat(decoded).hasSize(12 + plaintext.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11, 12, 27, 28})
    void storedValueCutShort_failsWithEncryptionException(int keptBytes) {
        byte[] decoded = Base64.getDecoder().decode(appConverter.convertToDatabaseColumn("visitor@example.com"));
        String truncated = Base64.getEncoder().encodeToString(Arrays.copyOf(decoded, keptBytes));

        assertThatThrownBy(() -> appConverter.convertToEntityAttribute(truncated))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void base64OfAPlaintextThatWasNeverEncrypted_isNotReturnedAsIs() {
        String notEncrypted = Base64.getEncoder()
                .encodeToString("a-plaintext-long-enough-to-pass-for-iv-and-tag@example.com".getBytes(
                        StandardCharsets.UTF_8));

        assertThatThrownBy(() -> appConverter.convertToEntityAttribute(notEncrypted))
                .isInstanceOf(EncryptionException.class);
    }
}
