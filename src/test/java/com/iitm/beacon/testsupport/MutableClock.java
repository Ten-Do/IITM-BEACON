package com.iitm.beacon.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} whose "now" can be advanced explicitly, so a single
 * long-lived component under test (e.g. {@code OtpService}) can observe a
 * different instant at each interaction within one test. Always uses
 * {@link ZoneOffset#UTC}.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        this.current = new AtomicReference<>(initial);
    }

    public void advanceTo(Instant instant) {
        current.set(instant);
    }

    public void advanceBy(Duration duration) {
        current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock is fixed to UTC");
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
