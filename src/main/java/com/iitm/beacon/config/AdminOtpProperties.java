package com.iitm.beacon.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code admin.otp.*} config: TTL/max-attempts for the single admin's
 * OTP, plus request-rate limits per email and per IP (decision 4).
 */
@ConfigurationProperties(prefix = "admin.otp")
public record AdminOtpProperties(
        Duration ttl,
        int maxAttempts,
        int requestLimitPerEmail,
        Duration requestWindowPerEmail,
        int requestLimitPerIp,
        Duration requestWindowPerIp) {
}
