package com.iitm.beacon.adminauth;

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
import com.iitm.beacon.config.AdminOtpProperties;
import com.iitm.beacon.config.AdminProperties;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * NFR-ADMIN-OTP-BRUTEFORCE: the attempt limit and the TTL are configurable,
 * not hard-wired to the shipped defaults (5 attempts, 5 minutes, which
 * {@link OtpServiceTest} covers). Built here with non-default bounds — so a
 * service that ignored its {@link AdminOtpProperties} and used 5/5m would
 * fail every test. Rate limiting is stubbed to always allow, as in {@link
 * OtpServiceTest}.
 */
class OtpServiceConfigurabilityTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String IP = "203.0.113.10";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final OtpMailer otpMailer = mock(OtpMailer.class);

    private OtpService serviceWith(int maxAttempts, Duration ttl) {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(anyString(), any(), anyInt(), any())).thenReturn(true);
        AdminOtpProperties properties = new AdminOtpProperties(
                ttl, maxAttempts, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1));
        return new OtpService(
                new AdminProperties(ADMIN_EMAIL),
                properties,
                new OtpCodeGenerator(),
                otpMailer,
                clock,
                rateLimiterService);
    }

    private String requestCode(OtpService service) {
        service.requestOtp(ADMIN_EMAIL, IP);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer).sendOtp(eq(ADMIN_EMAIL), captor.capture());
        return captor.getValue();
    }

    private static String wrongCodeFor(String code) {
        return "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ";
    }

    @Test
    void maxAttemptsThree_twoWrongCodes_theCorrectOneStillVerifies() {
        OtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);

        assertThat(service.verify(ADMIN_EMAIL, wrongCodeFor(code)))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(service.verify(ADMIN_EMAIL, wrongCodeFor(code)))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));

        assertThat(service.verify(ADMIN_EMAIL, code)).isEqualTo(new OtpVerifyResult.Verified(ADMIN_EMAIL));
    }

    @Test
    void maxAttemptsThree_thirdWrongCode_exhaustsTheOtp_andTheCorrectCodeNoLongerVerifies() {
        OtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);
        service.verify(ADMIN_EMAIL, wrongCodeFor(code));
        service.verify(ADMIN_EMAIL, wrongCodeFor(code));

        assertThat(service.verify(ADMIN_EMAIL, wrongCodeFor(code)))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
        assertThat(service.verify(ADMIN_EMAIL, code))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void maxAttemptsOne_theFirstWrongCode_alreadyExhaustsTheOtp() {
        OtpService service = serviceWith(1, Duration.ofMinutes(2));
        String code = requestCode(service);

        assertThat(service.verify(ADMIN_EMAIL, wrongCodeFor(code)))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
        assertThat(service.verify(ADMIN_EMAIL, code))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void ttlTwoMinutes_correctCodeExactlyAtExpiry_stillVerifies() {
        OtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);

        clock.advanceTo(START.plus(Duration.ofMinutes(2)));

        assertThat(service.verify(ADMIN_EMAIL, code)).isEqualTo(new OtpVerifyResult.Verified(ADMIN_EMAIL));
    }

    @Test
    void ttlTwoMinutes_correctCodeOneNanosecondAfterExpiry_isExpired() {
        OtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);

        clock.advanceTo(START.plus(Duration.ofMinutes(2)).plusNanos(1));

        assertThat(service.verify(ADMIN_EMAIL, code))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.EXPIRED));
    }

    @Test
    void ttlTwoMinutes_expiredOtpIsInvalidated_soItStaysUnusableEvenIfTheClockWentBack() {
        OtpService service = serviceWith(3, Duration.ofMinutes(2));
        String code = requestCode(service);
        clock.advanceTo(START.plus(Duration.ofMinutes(2)).plusNanos(1));
        service.verify(ADMIN_EMAIL, code);

        clock.advanceTo(START);

        assertThat(service.verify(ADMIN_EMAIL, code))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }
}
