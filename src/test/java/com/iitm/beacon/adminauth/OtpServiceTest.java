package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.config.AdminOtpProperties;
import com.iitm.beacon.config.AdminProperties;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtpServiceTest {

    private static final String ADMIN_EMAIL = "admin@example.com";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final AdminProperties adminProperties = new AdminProperties(ADMIN_EMAIL);
    private final AdminOtpProperties otpProperties = new AdminOtpProperties(
            Duration.ofMinutes(5), 5, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1));
    private final OtpCodeGenerator codeGenerator = new OtpCodeGenerator();
    private OtpMailer otpMailer;
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpMailer = mock(OtpMailer.class);
        otpService = new OtpService(adminProperties, otpProperties, codeGenerator, otpMailer, clock);
    }

    @Test
    void requestOtpWithMatchingEmailGeneratesStateAndSendsMail() {
        otpService.requestOtp(ADMIN_EMAIL);

        verify(otpMailer, times(1)).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void requestOtpWithMatchingEmail_thenCorrectCodeVerifiesSuccessfully() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL);
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, codeCaptor.getValue());

        assertThat(result).isInstanceOf(OtpVerifyResult.Verified.class);
        assertThat(((OtpVerifyResult.Verified) result).email()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void requestOtpWithNonMatchingEmail_isANoOp_andDoesNotSendMail() {
        otpService.requestOtp("someone-else@example.com");

        verify(otpMailer, never()).sendOtp(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void requestOtpWithNonMatchingEmail_leavesPendingLegitimateOtpUntouched() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL);
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String originalCode = codeCaptor.getValue();

        otpService.requestOtp("intruder@example.com");

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, originalCode);
        assertThat(result).isInstanceOf(OtpVerifyResult.Verified.class);
    }

    @Test
    void verifyWithNoPriorRequest_isRejectedAsNoPendingOtp() {
        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, "ABC234");

        assertThat(result).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void verifySameCodeTwice_secondAttemptIsNoPendingOtp_oneTimeUse() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL);
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();

        assertThat(otpService.verify(ADMIN_EMAIL, code)).isInstanceOf(OtpVerifyResult.Verified.class);
        assertThat(otpService.verify(ADMIN_EMAIL, code))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void verifyWithWrongCode_isRejectedAsWrongCode_andCorrectCodeStillWorksAfter() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL);
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();

        OtpVerifyResult wrongResult = otpService.verify(ADMIN_EMAIL, "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ");
        assertThat(wrongResult).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));

        OtpVerifyResult correctResult = otpService.verify(ADMIN_EMAIL, code);
        assertThat(correctResult).isInstanceOf(OtpVerifyResult.Verified.class);
    }

    @Test
    void maxAttemptsBoundary_fourWrongAttemptsStillAllowsFifthCorrectAttempt() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL); // maxAttempts = 5
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();
        String wrongCode = wrongCodeFor(code);

        for (int i = 0; i < 4; i++) {
            assertThat(otpService.verify(ADMIN_EMAIL, wrongCode))
                    .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        }

        assertThat(otpService.verify(ADMIN_EMAIL, code)).isInstanceOf(OtpVerifyResult.Verified.class);
    }

    @Test
    void maxAttemptsBoundary_fifthWrongAttemptExhaustsAndInvalidatesOtp() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL); // maxAttempts = 5
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();
        String wrongCode = wrongCodeFor(code);

        for (int i = 0; i < 4; i++) {
            otpService.verify(ADMIN_EMAIL, wrongCode);
        }
        OtpVerifyResult fifthResult = otpService.verify(ADMIN_EMAIL, wrongCode);
        assertThat(fifthResult).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));

        OtpVerifyResult afterExhaustion = otpService.verify(ADMIN_EMAIL, code);
        assertThat(afterExhaustion).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void ttlBoundary_exactlyAtExpiresAtStillSucceeds() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL);
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();

        clock.advanceBy(Duration.ofMinutes(5)); // exactly at expiresAt

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, code);
        assertThat(result).isInstanceOf(OtpVerifyResult.Verified.class);
    }

    @Test
    void ttlBoundary_oneInstantAfterExpiresAtIsExpired() {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL);
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();

        clock.advanceBy(Duration.ofMinutes(5).plusNanos(1));

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, code);
        assertThat(result).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.EXPIRED));
    }

    @Test
    void requestOtpWithNullEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> otpService.requestOtp(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestOtpWithBlankEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> otpService.requestOtp("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyWithBlankCode_neverMatches_rejectedAsWrongCode() {
        otpService.requestOtp(ADMIN_EMAIL);

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, "");
        assertThat(result).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
    }

    @Test
    void verifyWithNullCode_neverMatches_rejectedAsWrongCode() {
        otpService.requestOtp(ADMIN_EMAIL);

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, null);
        assertThat(result).isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
    }

    @Test
    void verifyWithNullEmail_throwsIllegalArgumentException() {
        otpService.requestOtp(ADMIN_EMAIL);

        assertThatThrownBy(() -> otpService.verify(null, "ABC234")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentWrongVerifyAttempts_bothDecrementsAreApplied_noLostUpdate() throws InterruptedException {
        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        otpService.requestOtp(ADMIN_EMAIL); // maxAttempts = 5
        verify(otpMailer).sendOtp(org.mockito.ArgumentMatchers.eq(ADMIN_EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();
        String wrongCode = wrongCodeFor(code);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                otpService.verify(ADMIN_EMAIL, wrongCode);
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // maxAttempts = 5; two decrements applied -> 3 remaining -> 3 more wrong
        // attempts should exhaust it (not more, not fewer), proving no lost update.
        assertThat(otpService.verify(ADMIN_EMAIL, wrongCode))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(otpService.verify(ADMIN_EMAIL, wrongCode))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(otpService.verify(ADMIN_EMAIL, wrongCode))
                .isEqualTo(new OtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
    }

    private static String wrongCodeFor(String code) {
        return "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ";
    }
}
