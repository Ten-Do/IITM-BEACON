package com.iitm.beacon.adminauth;

import com.iitm.beacon.common.EmailNormalizer;
import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.AdminOtpProperties;
import com.iitm.beacon.config.AdminProperties;
import com.iitm.beacon.config.OtpMailer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Holds {@code { code, expiresAt, attemptsRemaining }} for the single admin
 * email in memory — no DB table (decision 4). Single mutable field guarded by
 * {@code synchronized} methods, since there's only ever one admin and one
 * live OTP at a time.
 *
 * <p>{@link #requestOtp} also owns the per-email/per-IP rate-limit check
 * (moved down from {@code AdminAuthController}) so any caller — the REST
 * controller today, a Thymeleaf view-controller later — gets the same
 * throttling without duplicating the logic.
 */
@Service
public class OtpService {

    private static final String RATE_LIMIT_EMAIL_KEY = "admin-otp-request:email";
    private static final String RATE_LIMIT_IP_KEY = "admin-otp-request:ip";

    private final AdminProperties adminProperties;
    private final AdminOtpProperties otpProperties;
    private final OtpCodeGenerator codeGenerator;
    private final OtpMailer otpMailer;
    private final Clock clock;
    private final RateLimiterService rateLimiterService;

    private OtpState state;

    public OtpService(
            AdminProperties adminProperties,
            AdminOtpProperties otpProperties,
            OtpCodeGenerator codeGenerator,
            OtpMailer otpMailer,
            Clock clock,
            RateLimiterService rateLimiterService) {
        this.adminProperties = adminProperties;
        this.otpProperties = otpProperties;
        this.codeGenerator = codeGenerator;
        this.otpMailer = otpMailer;
        this.clock = clock;
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Enforces the per-email and per-IP OTP request rate limits, then — if
     * within budget — generates/stores/sends the OTP exactly as before.
     *
     * @param ip caller's remote address, used as the per-IP rate-limit key;
     *     may be {@code null} (treated as an opaque, shared key).
     * @throws TooManyRequestsException if either rate limit is exceeded.
     */
    public synchronized void requestOtp(String rawEmail, String ip) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        boolean allowed = rateLimiterService.tryConsume(
                        RATE_LIMIT_EMAIL_KEY,
                        normalized,
                        otpProperties.requestLimitPerEmail(),
                        otpProperties.requestWindowPerEmail())
                && rateLimiterService.tryConsume(
                        RATE_LIMIT_IP_KEY, ip, otpProperties.requestLimitPerIp(), otpProperties.requestWindowPerIp());
        if (!allowed) {
            throw new TooManyRequestsException("Too many OTP requests in a short window.");
        }
        if (!normalized.equals(EmailNormalizer.normalize(adminProperties.email()))) {
            return;
        }
        String code = codeGenerator.generate();
        this.state = new OtpState(code, clock.instant().plus(otpProperties.ttl()), otpProperties.maxAttempts());
        otpMailer.sendOtp(normalized, code);
    }

    public synchronized OtpVerifyResult verify(String rawEmail, String rawCode) {
        EmailNormalizer.normalize(rawEmail); // validates presence; admin identity is fixed regardless of input
        if (state == null) {
            return new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP);
        }
        if (clock.instant().isAfter(state.expiresAt())) {
            state = null;
            return new OtpVerifyResult.Rejected(RejectionReason.EXPIRED);
        }
        if (state.attemptsRemaining() <= 0) {
            state = null;
            return new OtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED);
        }
        boolean matches = rawCode != null
                && MessageDigest.isEqual(
                        state.code().getBytes(StandardCharsets.UTF_8), rawCode.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            int remaining = state.attemptsRemaining() - 1;
            if (remaining <= 0) {
                state = null;
                return new OtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED);
            }
            state = new OtpState(state.code(), state.expiresAt(), remaining);
            return new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE);
        }
        String verifiedEmail = EmailNormalizer.normalize(adminProperties.email());
        state = null;
        return new OtpVerifyResult.Verified(verifiedEmail);
    }
}
