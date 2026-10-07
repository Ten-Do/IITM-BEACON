package com.iitm.beacon.perf;

import java.util.Arrays;

/**
 * The nearest-rank p50, p95 and p99 and the maximum of a set of latency
 * samples, in the samples' own unit (nanoseconds throughout this package).
 */
record LatencySummary(int count, long p50, long p95, long p99, long max) {

    /** @throws IllegalArgumentException with no samples */
    static LatencySummary of(long[] samples) {
        return new LatencySummary(
                samples.length,
                Percentiles.nearestRank(samples, 50),
                Percentiles.nearestRank(samples, 95),
                Percentiles.nearestRank(samples, 99),
                Arrays.stream(samples).max().orElseThrow());
    }
}
