package com.iitm.beacon.submission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iitm.beacon.common.EmailNormalizer;
import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.config.VisitorOtpProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Concurrent-safe, in-memory OTP state for visitors, keyed by normalized
 * email (decision 17) — the visitor-scale analogue of {@code
 * adminauth.OtpService}: same OTP style, but keyed and concurrent-safe since
 * many visitors can hold a live OTP at once.
 */
@Service
public class VisitorOtpService {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final VisitorOtpProperties otpProperties;
    private final OtpCodeGenerator codeGenerator;
    private final OtpMailer otpMailer;
    private final Clock clock;
    private final Cache<String, VisitorOtpState> cache;

    public VisitorOtpService(
            VisitorOtpProperties otpProperties, OtpCodeGenerator codeGenerator, OtpMailer otpMailer, Clock clock) {
        this.otpProperties = otpProperties;
        this.codeGenerator = codeGenerator;
        this.otpMailer = otpMailer;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(otpProperties.ttl().plus(Duration.ofMinutes(5)))
                .build();
    }

    public void requestOtp(String rawEmail) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        String code = codeGenerator.generate();
        String hash = sha256Hex(code);
        cache.put(
                normalized,
                new VisitorOtpState(hash, clock.instant().plus(otpProperties.ttl()), otpProperties.maxAttempts()));
        otpMailer.sendOtp(normalized, code);
    }

    public VisitorOtpVerifyResult verify(String rawEmail, String rawCode) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        AtomicReference<VisitorOtpVerifyResult> outcome = new AtomicReference<>();
        cache.asMap().compute(normalized, (key, existing) -> {
            if (existing == null) {
                outcome.set(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
                return null;
            }
            if (clock.instant().isAfter(existing.expiresAt())) {
                outcome.set(new VisitorOtpVerifyResult.Rejected(RejectionReason.EXPIRED));
                return null;
            }
            if (existing.attemptsRemaining() <= 0) {
                outcome.set(new VisitorOtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
                return null;
            }
            boolean matches = rawCode != null
                    && MessageDigest.isEqual(
                            existing.codeHash().getBytes(StandardCharsets.UTF_8),
                            sha256Hex(rawCode).getBytes(StandardCharsets.UTF_8));
            if (!matches) {
                int remaining = existing.attemptsRemaining() - 1;
                if (remaining <= 0) {
                    outcome.set(new VisitorOtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
                    return null;
                }
                outcome.set(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
                return new VisitorOtpState(existing.codeHash(), existing.expiresAt(), remaining);
            }
            outcome.set(new VisitorOtpVerifyResult.Verified(normalized));
            return null;
        });
        return outcome.get();
    }

    /**
     * Test-only accessor exposing the stored code hash for a given
     * (already-normalized) email, so tests can assert the plaintext code is
     * never stored without reaching into Caffeine internals via reflection.
     * Package-private — never used outside tests.
     */
    String peekCodeHash(String normalizedEmail) {
        VisitorOtpState existing = cache.getIfPresent(normalizedEmail);
        return existing == null ? null : existing.codeHash();
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
