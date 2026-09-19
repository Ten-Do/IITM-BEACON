package com.iitm.beacon.common.ratelimit;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Instant;

/**
 * Adapts the app's shared {@link Clock} bean to Bucket4j's {@link TimeMeter}
 * contract, so rate-limiting stays deterministic and testable the same way
 * every other time-based component in this codebase does (see
 * docs/architecture.md §16), instead of Bucket4j reading the system clock
 * directly.
 */
public class ClockTimeMeter implements TimeMeter {

    private final Clock clock;

    public ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
