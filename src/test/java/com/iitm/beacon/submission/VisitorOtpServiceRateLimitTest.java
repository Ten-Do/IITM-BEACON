package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.config.VisitorOtpProperties;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code VisitorOtpService.requestOtp} itself owns the
 * per-email/per-IP rate-limit check (moved down from {@code
 * VisitorAuthController} so the upcoming view-controller can reuse the same
 * flow without duplicating the throttling logic) — the visitor analogue of
 * {@code OtpServiceRateLimitTest}, using a real {@link RateLimiterService} so
 * thresholds and windows are exercised end-to-end.
 */
class VisitorOtpServiceRateLimitTest {

    private static final String EMAIL = "visitor@example.com";
    private static final String OTHER_EMAIL = "other-visitor@example.com";
    private static final String IP_A = "203.0.113.10";
    private static final String IP_B = "203.0.113.20";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final OtpCodeGenerator codeGenerator = new OtpCodeGenerator();
    private OtpMailer otpMailer;

    private VisitorOtpService serviceWithLimits(int limitPerEmail, int limitPerIp) {
        VisitorOtpProperties otpProperties = new VisitorOtpProperties(
                Duration.ofMinutes(5), 5, limitPerEmail, Duration.ofMinutes(1), limitPerIp, Duration.ofMinutes(1));
        return new VisitorOtpService(otpProperties, codeGenerator, otpMailer, clock, new RateLimiterService(clock));
    }

    @BeforeEach
    void setUp() {
        otpMailer = mock(OtpMailer.class);
    }

    @Test
    void requestWithinPerEmailLimit_succeeds() {
        VisitorOtpService service = serviceWithLimits(1, 5);

        service.requestOtp(EMAIL, IP_A);

        verify(otpMailer).sendOtp(anyString(), anyString());
    }

    @Test
    void secondRequestForSameEmailWithinWindow_exceedsPerEmailLimit_throwsAndSendsNoAdditionalMail() {
        VisitorOtpService service = serviceWithLimits(1, 5);
        service.requestOtp(EMAIL, IP_A); // consumes the only budget; sends the one legitimate mail

        assertThatThrownBy(() -> service.requestOtp(EMAIL, IP_A)).isInstanceOf(TooManyRequestsException.class);

        // Exactly the first call's mail was sent — the rejected second call sent none.
        verify(otpMailer, org.mockito.Mockito.times(1)).sendOtp(anyString(), anyString());
    }

    @Test
    void secondRequestFromSameIpDifferentEmail_exceedsPerIpLimit_throws() {
        VisitorOtpService service = serviceWithLimits(5, 1);
        service.requestOtp(EMAIL, IP_A);

        assertThatThrownBy(() -> service.requestOtp(OTHER_EMAIL, IP_A))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void differentIpsForSameEmail_doNotShareThePerEmailBudget_secondStillRejectedByEmailLimit() {
        VisitorOtpService service = serviceWithLimits(1, 5);
        service.requestOtp(EMAIL, IP_A);

        assertThatThrownBy(() -> service.requestOtp(EMAIL, IP_B)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void differentEmailsFromDifferentIps_areIndependentlyBudgeted_bothSucceed() {
        VisitorOtpService service = serviceWithLimits(1, 1);

        service.requestOtp(EMAIL, IP_A);
        service.requestOtp(OTHER_EMAIL, IP_B);

        verify(otpMailer, org.mockito.Mockito.times(2)).sendOtp(anyString(), anyString());
    }

    @Test
    void afterWindowElapses_requestIsAllowedAgain() {
        VisitorOtpService service = serviceWithLimits(1, 5);
        service.requestOtp(EMAIL, IP_A);
        assertThatThrownBy(() -> service.requestOtp(EMAIL, IP_A)).isInstanceOf(TooManyRequestsException.class);

        clock.advanceBy(Duration.ofMinutes(1).plusSeconds(1));

        service.requestOtp(EMAIL, IP_A);
        verify(otpMailer, org.mockito.Mockito.times(2)).sendOtp(anyString(), anyString());
    }

    @Test
    void invalidEmailIsRejectedBeforeConsumingAnyRateLimitBudget() {
        VisitorOtpService service = serviceWithLimits(1, 5);

        assertThatThrownBy(() -> service.requestOtp(null, IP_A)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requestOtp("   ", IP_A)).isInstanceOf(IllegalArgumentException.class);

        // Budget of 1 for this email/IP pair is still untouched.
        service.requestOtp(EMAIL, IP_A);
        verify(otpMailer).sendOtp(anyString(), anyString());
    }

    @Test
    void nullIp_doesNotThrowNpe_stillSendsOtp() {
        VisitorOtpService service = serviceWithLimits(1, 5);

        service.requestOtp(EMAIL, null);

        verify(otpMailer).sendOtp(anyString(), anyString());
    }
}
