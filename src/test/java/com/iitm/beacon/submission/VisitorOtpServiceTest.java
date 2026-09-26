package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.config.VisitorOtpProperties;
import com.iitm.beacon.testsupport.MutableClock;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers {@code VisitorOtpService}'s own OTP request/verify semantics (TTL,
 * max attempts, one-time-use, per-email keying). Rate-limiting is a separate
 * concern the service now also owns — see {@link VisitorOtpServiceRateLimitTest}
 * — so the {@code RateLimiterService} collaborator here is stubbed to always
 * allow, keeping these tests decoupled from throttling thresholds.
 */
class VisitorOtpServiceTest {

    private static final String EMAIL = "visitor@example.com";
    private static final String IP = "203.0.113.10";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final VisitorOtpProperties otpProperties = new VisitorOtpProperties(
            Duration.ofMinutes(5), 5, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1));
    private final OtpCodeGenerator codeGenerator = new OtpCodeGenerator();
    private OtpMailer otpMailer;
    private RateLimiterService rateLimiterService;
    private VisitorOtpService visitorOtpService;
    private final java.util.Map<String, Integer> requestCounts = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        otpMailer = mock(OtpMailer.class);
        rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(anyString(), any(), anyInt(), any())).thenReturn(true);
        visitorOtpService = new VisitorOtpService(otpProperties, codeGenerator, otpMailer, clock, rateLimiterService);
    }

    private String requestAndCaptureCode(String email) {
        String normalized = com.iitm.beacon.common.EmailNormalizer.normalize(email);
        int expectedInvocations = requestCounts.merge(normalized, 1, Integer::sum);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        visitorOtpService.requestOtp(email, IP);
        verify(otpMailer, times(expectedInvocations)).sendOtp(eq(normalized), captor.capture());
        return captor.getValue();
    }

    private static String wrongCodeFor(String code) {
        return "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ";
    }

    @Test
    void requestThenVerifyWithCorrectCode_succeeds() {
        String code = requestAndCaptureCode(EMAIL);

        VisitorOtpVerifyResult result = visitorOtpService.verify(EMAIL, code);

        assertThat(result).isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void verifyWithNoPriorRequest_isRejectedAsNoPendingOtp() {
        VisitorOtpVerifyResult result = visitorOtpService.verify(EMAIL, "ABC234");

        assertThat(result).isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void verifySameCodeTwice_secondAttemptIsNoPendingOtp_oneTimeUse() {
        String code = requestAndCaptureCode(EMAIL);

        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void verifyWithWrongCode_isRejectedAsWrongCode_andCorrectCodeStillWorksAfter() {
        String code = requestAndCaptureCode(EMAIL);
        String wrongCode = wrongCodeFor(code);

        assertThat(visitorOtpService.verify(EMAIL, wrongCode))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void maxAttemptsBoundary_fourWrongAttemptsStillAllowsFifthCorrectAttempt() {
        String code = requestAndCaptureCode(EMAIL); // maxAttempts = 5
        String wrongCode = wrongCodeFor(code);

        for (int i = 0; i < 4; i++) {
            assertThat(visitorOtpService.verify(EMAIL, wrongCode))
                    .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        }

        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void maxAttemptsBoundary_fifthWrongAttemptExhaustsAndInvalidatesOtp() {
        String code = requestAndCaptureCode(EMAIL);
        String wrongCode = wrongCodeFor(code);

        for (int i = 0; i < 4; i++) {
            visitorOtpService.verify(EMAIL, wrongCode);
        }
        assertThat(visitorOtpService.verify(EMAIL, wrongCode))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));

        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.NO_PENDING_OTP));
    }

    @Test
    void ttlBoundary_exactlyAtExpiresAtStillSucceeds() {
        String code = requestAndCaptureCode(EMAIL);

        clock.advanceBy(Duration.ofMinutes(5));

        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void ttlBoundary_oneInstantAfterExpiresAtIsExpired() {
        String code = requestAndCaptureCode(EMAIL);

        clock.advanceBy(Duration.ofMinutes(5).plusNanos(1));

        assertThat(visitorOtpService.verify(EMAIL, code))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.EXPIRED));
    }

    @Test
    void requestOtpWithNullEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> visitorOtpService.requestOtp(null, IP)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestOtpWithBlankEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> visitorOtpService.requestOtp("   ", IP)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyWithNullEmail_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> visitorOtpService.verify(null, "ABC234"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyWithNullCode_neverMatches_rejectedAsWrongCode() {
        requestAndCaptureCode(EMAIL);

        assertThat(visitorOtpService.verify(EMAIL, null))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
    }

    @Test
    void verifyWithBlankCode_neverMatches_rejectedAsWrongCode() {
        requestAndCaptureCode(EMAIL);

        assertThat(visitorOtpService.verify(EMAIL, ""))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
    }

    @Test
    void storedCodeHashIsNotThePlaintextCode() throws Exception {
        String code = requestAndCaptureCode(EMAIL);

        String storedHash = visitorOtpService.peekCodeHash(
                com.iitm.beacon.common.EmailNormalizer.normalize(EMAIL));

        assertThat(storedHash).isNotNull();
        assertThat(storedHash).isNotEqualTo(code);
        byte[] codeBytes = code.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expectedHash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(codeBytes));
        assertThat(storedHash).isEqualTo(expectedHash);
    }

    @Test
    void requestingOtpTwiceForSameEmail_replacesPendingOtp_onlySecondCodeVerifies() {
        String firstCode = requestAndCaptureCode(EMAIL);
        String secondCode = requestAndCaptureCode(EMAIL);
        assertThat(firstCode).isNotEqualTo(secondCode);

        assertThat(visitorOtpService.verify(EMAIL, firstCode))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(visitorOtpService.verify(EMAIL, secondCode))
                .isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void concurrentVerifyAttemptsForSameEmail_exactlyExpectedDecrementsApplied() throws InterruptedException {
        String code = requestAndCaptureCode(EMAIL); // maxAttempts = 5
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
                visitorOtpService.verify(EMAIL, wrongCode);
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 5 - 2 = 3 remaining; 3 more wrong attempts should exhaust exactly.
        assertThat(visitorOtpService.verify(EMAIL, wrongCode))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(visitorOtpService.verify(EMAIL, wrongCode))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.WRONG_CODE));
        assertThat(visitorOtpService.verify(EMAIL, wrongCode))
                .isEqualTo(new VisitorOtpVerifyResult.Rejected(RejectionReason.ATTEMPTS_EXHAUSTED));
    }

    @Test
    void manyDifferentEmailsProcessedConcurrently_noCrossEmailInterference() throws InterruptedException {
        int emailCount = 50;
        String[] emails = new String[emailCount];
        String[] codes = new String[emailCount];
        for (int i = 0; i < emailCount; i++) {
            emails[i] = "visitor" + i + "@example.com";
            codes[i] = requestAndCaptureCode(emails[i]);
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch done = new CountDownLatch(emailCount);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < emailCount; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    VisitorOtpVerifyResult result = visitorOtpService.verify(emails[idx], codes[idx]);
                    if (result instanceof VisitorOtpVerifyResult.Verified) {
                        successes.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(emailCount);
    }
}
