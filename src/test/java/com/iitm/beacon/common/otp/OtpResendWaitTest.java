package com.iitm.beacon.common.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * How long a login code page's "Resend code" waits: the time for one more
 * per-email code request to become available again — the per-email window
 * divided by the requests it allows (the rate limiter refills its tokens
 * evenly over the window), in whole seconds, rounded up so the button never
 * comes back a moment before the server would accept the request.
 */
class OtpResendWaitTest {

    @ParameterizedTest(name = "{0} per {1} -> {2} s")
    @CsvSource({
        // the shipped default: one request per minute
        "1, PT1M, 60",
        "2, PT1M, 30",
        // a whole number of seconds is not rounded any further
        "3, PT3M, 60",
        // 8.57 s, rounded up
        "7, PT1M, 9",
        // just over a whole second is the next one
        "1, PT60.001S, 61",
        "1, PT60.000000001S, 61",
        // a wait under a second is still a second, never zero
        "1000, PT1M, 1",
        "1, PT0.5S, 1",
        "1, PT0.000000001S, 1",
        "2147483647, PT1M, 1",
        "1, PT2M, 120",
        "1, PT24H, 86400",
    })
    void seconds_isTheWindowOverTheLimit_roundedUp(int limit, Duration window, long expected) {
        assertThat(OtpResendWait.seconds(limit, window)).isEqualTo(expected);
    }

    /** A limit the rate limiter itself rejects can't give a wait either: no request would ever be allowed. */
    @ParameterizedTest
    @CsvSource({"0", "-1", "-2147483648"})
    void seconds_ofANonPositiveLimit_isRejected(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> OtpResendWait.seconds(limit, Duration.ofMinutes(1)));
    }

    /** Nor can a window the rate limiter rejects: zero, negative, or none at all. */
    @ParameterizedTest
    @CsvSource(value = {"PT0S", "-PT1S", "-PT0.000000001S", "null"}, nullValues = "null")
    void seconds_ofANonPositiveOrMissingWindow_isRejected(Duration window) {
        assertThatIllegalArgumentException().isThrownBy(() -> OtpResendWait.seconds(1, window));
    }
}
