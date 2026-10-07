package com.iitm.beacon.perf;

import java.util.Arrays;
import java.util.Objects;

/**
 * Nearest-rank percentiles: the p-th percentile of n samples is the
 * {@code ceil(p / 100 * n)}-th smallest sample — always one of the measured
 * values, never an interpolation between two, so a reported p95 is a
 * response time some request actually had.
 */
final class Percentiles {

    private Percentiles() {
    }

    /**
     * The {@code percentile}-th percentile of {@code samples}, which may be
     * in any order and are not modified.
     *
     * @param percentile greater than 0, at most 100 (100 is the maximum)
     * @throws IllegalArgumentException with no samples, or a percentile outside (0, 100]
     */
    static long nearestRank(long[] samples, double percentile) {
        Objects.requireNonNull(samples, "samples");
        if (samples.length == 0) {
            throw new IllegalArgumentException("No samples: a percentile of nothing is undefined");
        }
        if (!(percentile > 0 && percentile <= 100)) {
            throw new IllegalArgumentException("Percentile must be in (0, 100], was " + percentile);
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        // percentile * n is exact for a whole-number percentile, and so is
        // dividing a multiple of 100 by 100: p95 of 200 samples is rank 190, not 191.
        int rank = (int) Math.ceil(percentile * sorted.length / 100.0);
        return sorted[Math.max(rank, 1) - 1];
    }
}
