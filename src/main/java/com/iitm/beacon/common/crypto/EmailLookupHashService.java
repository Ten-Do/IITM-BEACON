package com.iitm.beacon.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Computes the deterministic HMAC-SHA256 {@code email_lookup_hash} used only
 * to answer "does a testimonial already exist for this email" (decision 6).
 * Never round-trips back to plaintext — it only proves equality.
 */
@Service
public class EmailLookupHashService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final CryptoProperties cryptoProperties;

    public EmailLookupHashService(CryptoProperties cryptoProperties) {
        this.cryptoProperties = cryptoProperties;
    }

    public String hash(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("email must not be null or blank");
        }
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    cryptoProperties.hmacPepper().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to compute email lookup hash", e);
        }
    }
}
