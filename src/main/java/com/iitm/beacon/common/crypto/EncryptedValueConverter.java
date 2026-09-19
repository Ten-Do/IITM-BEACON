package com.iitm.beacon.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM {@link AttributeConverter}, applied only where explicitly
 * referenced via {@code @Convert(converter = EncryptedValueConverter.class)}
 * (decision 6) — deliberately not {@code autoApply = true}. Each call to
 * {@link #convertToDatabaseColumn(String)} generates a fresh random 12-byte
 * IV, prepends it to the ciphertext, and base64-encodes the result for
 * storage; {@link #convertToEntityAttribute(String)} reverses that. GCM's
 * authentication tag makes a tampered ciphertext fail to decrypt rather than
 * silently returning garbage.
 *
 * <p>Final: the constructor validates {@code cryptoProperties} and can throw
 * before fully initializing the object; a non-final class would leave that
 * exposed to the finalizer-attack pattern (a subclass overriding {@code
 * finalize()} could observe/resurrect a partially-constructed instance).
 */
@Component
@Converter
public final class EncryptedValueConverter implements AttributeConverter<String, String> {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec secretKey;

    public EncryptedValueConverter(CryptoProperties cryptoProperties) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(cryptoProperties.encryptionKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("beacon.crypto.encryption-key is not valid base64", e);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "beacon.crypto.encryption-key must decode to exactly 32 bytes, but decoded to "
                            + keyBytes.length + " bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
            if (combined.length < IV_LENGTH_BYTES) {
                throw new EncryptionException("Stored value is too short to contain an IV", null);
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to decrypt value", e);
        }
    }
}
