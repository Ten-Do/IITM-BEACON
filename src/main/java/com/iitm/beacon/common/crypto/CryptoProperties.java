package com.iitm.beacon.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code beacon.crypto.*} config. {@code encryptionKey} must be a
 * base64-encoded 32-byte AES-256 key; {@code hmacPepper} is an arbitrary
 * secret string used to pepper the email lookup HMAC (see decision 6).
 */
@ConfigurationProperties(prefix = "beacon.crypto")
public record CryptoProperties(String encryptionKey, String hmacPepper) {
}
