package com.iitm.beacon.common.crypto;

/**
 * Wraps low-level cryptographic failures (e.g. authentication-tag mismatch on
 * a tampered ciphertext) from {@link EncryptedValueConverter} into an
 * unchecked exception the rest of the app can handle uniformly.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
