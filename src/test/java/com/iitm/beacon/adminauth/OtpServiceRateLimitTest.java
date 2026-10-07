package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.AdminOtpProperties;
import com.iitm.beacon.config.AdminProperties;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code OtpService.requestOtp} itself owns the per-email/per-IP
 * rate-limit check (moved down from {@code AdminAuthController} so the
 * upcoming view-controller can reuse the same flow without duplicating the
 * throttling logic) — using a real {@link RateLimiterService} so thresholds
 * and windows are exercised end-to-end, exactly as {@code
 * AdminAuthControllerRateLimitTest} does at the HTTP layer.
 */
class OtpServiceRateLimitTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String IP_A = "203.0.113.10";
    private static final String IP_B = "203.0.113.20";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final AdminProperties adminProperties = new AdminProperties(ADMIN_EMAIL);
    private final OtpCodeGenerator codeGenerator = new OtpCodeGenerator();
    private OtpMailer otpMailer;

    private OtpService serviceWithLimits(int limitPerEmail, int limitPerIp) {
        AdminOtpProperties otpProperties = new AdminOtpProperties(
                Duration.ofMinutes(5), 5, limitPerEmail, Duration.ofMinutes(1), limitPerIp, Duration.ofMinutes(1));
        return new OtpService(
                adminProperties, otpProperties, codeGenerator, otpMailer, clock, new RateLimiterService(clock));
    }

    @BeforeEach
    void setUp() {
        otpMailer = mock(OtpMailer.class);
    }

    @Test
    void requestWithinPerEmailLimit_succeeds() {
        OtpService otpService = serviceWithLimits(1, 5);

        otpService.requestOtp(ADMIN_EMAIL, IP_A);

        verify(otpMailer).sendOtp(anyString(), anyString());
    }

    @Test
    void secondRequestForSameEmailWithinWindow_exceedsPerEmailLimit_throwsAndSendsNoAdditionalMail() {
        OtpService otpService = serviceWithLimits(1, 5);
        otpService.requestOtp(ADMIN_EMAIL, IP_A); // consumes the only budget; sends the one legitimate mail

        assertThatThrownBy(() -> otpService.requestOtp(ADMIN_EMAIL, IP_A))
                .isInstanceOf(TooManyRequestsException.class);

        // Exactly the first call's mail was sent — the rejected second call sent none.
        verify(otpMailer, org.mockito.Mockito.times(1)).sendOtp(anyString(), anyString());
    }

    @Test
    void secondRequestFromSameIpDifferentEmail_exceedsPerIpLimit_throws() {
        OtpService otpService = serviceWithLimits(5, 1);
        otpService.requestOtp(ADMIN_EMAIL, IP_A);

        assertThatThrownBy(() -> otpService.requestOtp("someone-else@example.com", IP_A))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void differentIpsForSameEmail_doNotShareThePerEmailBudget_secondStillRejectedByEmailLimit() {
        OtpService otpService = serviceWithLimits(1, 5);
        otpService.requestOtp(ADMIN_EMAIL, IP_A);

        // Per-email limit of 1 is exceeded regardless of a different IP.
        assertThatThrownBy(() -> otpService.requestOtp(ADMIN_EMAIL, IP_B))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void differentEmailsFromDifferentIps_areIndependentlyBudgeted_bothSucceed() {
        OtpService otpService = serviceWithLimits(1, 1);

        otpService.requestOtp(ADMIN_EMAIL, IP_A);
        otpService.requestOtp("someone-else@example.com", IP_B);

        verify(otpMailer).sendOtp(anyString(), anyString());
    }

    @Test
    void afterWindowElapses_requestIsAllowedAgain() {
        OtpService otpService = serviceWithLimits(1, 5);
        otpService.requestOtp(ADMIN_EMAIL, IP_A);
        assertThatThrownBy(() -> otpService.requestOtp(ADMIN_EMAIL, IP_A))
                .isInstanceOf(TooManyRequestsException.class);

        clock.advanceBy(Duration.ofMinutes(1).plusSeconds(1));

        otpService.requestOtp(ADMIN_EMAIL, IP_A);
        verify(otpMailer, org.mockito.Mockito.times(2)).sendOtp(anyString(), anyString());
    }

    @Test
    void invalidEmailIsRejectedBeforeConsumingAnyRateLimitBudget() {
        OtpService otpService = serviceWithLimits(1, 5);

        assertThatThrownBy(() -> otpService.requestOtp(null, IP_A)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> otpService.requestOtp("   ", IP_A)).isInstanceOf(IllegalArgumentException.class);

        // Budget of 1 for this email/IP pair is still untouched.
        otpService.requestOtp(ADMIN_EMAIL, IP_A);
        verify(otpMailer).sendOtp(anyString(), anyString());
    }

    @Test
    void rateLimitAppliesEvenWhenEmailDoesNotMatchAdmin() {
        OtpService otpService = serviceWithLimits(1, 5);
        otpService.requestOtp("someone-else@example.com", IP_A);

        assertThatThrownBy(() -> otpService.requestOtp("someone-else@example.com", IP_A))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void nullIp_doesNotThrowNpe_stillSendsOtp() {
        OtpService otpService = serviceWithLimits(1, 5);

        otpService.requestOtp(ADMIN_EMAIL, null);

        verify(otpMailer).sendOtp(anyString(), anyString());
    }
}
