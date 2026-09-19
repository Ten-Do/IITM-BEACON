package com.iitm.beacon.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code visitor.otp.*} config: TTL/max-attempts for a visitor's OTP,
 * plus request-rate limits per email and per IP (decision 17), configured
 * independently from the admin's own OTP settings.
 */
@ConfigurationProperties(prefix = "visitor.otp")
public record VisitorOtpProperties(
        Duration ttl,
        int maxAttempts,
        int requestLimitPerEmail,
        Duration requestWindowPerEmail,
        int requestLimitPerIp,
        Duration requestWindowPerIp) {
}
