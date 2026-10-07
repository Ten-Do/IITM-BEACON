package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.config.VisitorOtpProperties;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * NFR-VISITOR-OTP-BRUTEFORCE: the visitor OTP's attempt limit and TTL are
 * configurable, not hard-wired to the shipped defaults (5 attempts, 5
 * minutes, which {@link VisitorOtpServiceTest} covers). Built here with
 * non-default bounds, so a service that ignored its {@link
 * VisitorOtpProperties} would fail. Rate limiting is stubbed to always
 * allow, as in {@link VisitorOtpServiceTest}.
 */
class VisitorOtpServiceConfigurabilityTest {

    private static final String EMAIL = "configurable-visitor@example.com";
    private static final String IP = "203.0.113.10";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final OtpMailer otpMailer = mock(OtpMailer.class);

    private VisitorOtpService serviceWith(int maxAttempts, Duration ttl) {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(anyString(), any(), anyInt(), any())).thenReturn(true);
        VisitorOtpProperties properties = new VisitorOtpProperties(
                ttl, maxAttempts, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1));
        return new VisitorOtpService(properties, new OtpCodeGenerator(), otpMailer, clock, rateLimiterService);
    }

    private String requestCode(VisitorOtpService service) {
        service.requestOtp(EMAIL, IP);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer).sendOtp(eq(EMAIL), captor.capture());
        return captor.getValue();
    }

    private static String wrongCodeFor(String code) {
        return "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ";
    }

    @Test
    void maxAttemptsThree_twoWrongCodes_theCorrectOneStillVerifies() {
        VisitorOtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);

        assertThat(service.verify(EMAIL, wrongCodeFor(code)))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(service.verify(EMAIL, wrongCodeFor(code)))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));

        assertThat(service.verify(EMAIL, code)).isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void maxAttemptsThree_thirdWrongCode_exhaustsTheOtp_andTheCorrectCodeNoLongerVerifies() {
        VisitorOtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);
        service.verify(EMAIL, wrongCodeFor(code));
        service.verify(EMAIL, wrongCodeFor(code));

        assertThat(service.verify(EMAIL, wrongCodeFor(code)))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
        assertThat(service.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void maxAttemptsOne_theFirstWrongCode_alreadyExhaustsTheOtp() {
        VisitorOtpService service = serviceWith(1, Duration.ofMinutes(2));
        String code = requestCode(service);

        assertThat(service.verify(EMAIL, wrongCodeFor(code)))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
        assertThat(service.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void ttlTwoMinutes_correctCodeExactlyAtExpiry_stillVerifies() {
        VisitorOtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);

        clock.advanceTo(START.plus(Duration.ofMinutes(2)));

        assertThat(service.verify(EMAIL, code)).isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void ttlTwoMinutes_correctCodeOneNanosecondAfterExpiry_isExpired() {
        VisitorOtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);

        clock.advanceTo(START.plus(Duration.ofMinutes(2)).plusNanos(1));

        assertThat(service.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.EXPIRED));
    }

    @Test
    void ttlTwoMinutes_expiredOtpIsInvalidated_soItStaysUnusableEvenIfTheClockWentBack() {
        VisitorOtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);
        clock.advanceTo(START.plus(Duration.ofMinutes(2)).plusNanos(1));
        service.verify(EMAIL, code);

        clock.advanceTo(START);

        assertThat(service.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }
}
