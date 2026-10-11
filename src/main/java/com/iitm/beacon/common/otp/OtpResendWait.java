package com.iitm.beacon.common.otp;

import java.time.Duration;

/**
 * How long a login code page's "Resend code" button waits before it can be
 * pressed (decision 36), for both the admin and the visitor login: the time
 * one more per-email code request takes to become available again. The
 * per-email rate limit ({@code common.ratelimit.RateLimiterService}) refills
 * its {@code requestLimitPerEmail} requests evenly over {@code
 * requestWindowPerEmail}, so that is the window divided by the limit — in
 * whole seconds, rounded up, so the button never comes back before the
 * server would take the request (and a wait under a second is a second).
 */
public final class OtpResendWait {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private OtpResendWait() {
    }

    /**
     * @throws IllegalArgumentException for a limit or window the rate limiter
     *     rejects as well: a non-positive limit, or a missing, zero or
     *     negative window.
     */
    public static long seconds(int requestLimitPerEmail, Duration requestWindowPerEmail) {
        if (requestLimitPerEmail <= 0) {
            throw new IllegalArgumentException("The per-email request limit must be positive");
        }
        if (requestWindowPerEmail == null || requestWindowPerEmail.isNegative() || requestWindowPerEmail.isZero()) {
            throw new IllegalArgumentException("The per-email request window must be positive");
        }
        return Math.ceilDiv(requestWindowPerEmail.toNanos(), requestLimitPerEmail * NANOS_PER_SECOND);
    }
}
