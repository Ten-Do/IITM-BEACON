package com.iitm.beacon.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Nearest-rank percentiles of latency samples: the p-th percentile of n
 * samples is the ceil(p/100 * n)-th smallest one — always a measured value,
 * never an interpolation.
 */
class PercentilesTest {

    /** 1, 2, ..., n in a scrambled (not sorted, not reversed) order. */
    private static long[] oneTo(int n) {
        return LongStream.rangeClosed(1, n).map(i -> (i * 7919) % n + 1).toArray();
    }

    @Test
    void nullSamples_areRejected() {
        assertThatThrownBy(() -> Percentiles.nearestRank(null, 50)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void noSamples_haveNoPercentile() {
        assertThatThrownBy(() -> Percentiles.nearestRank(new long[0], 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, 100.0001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void percentileOutsideZeroExclusiveToHundredInclusive_isRejected(double percentile) {
        assertThatThrownBy(() -> Percentiles.nearestRank(new long[] {1, 2, 3}, percentile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0001, 1, 50, 95, 99, 100})
    void oneSample_isEveryPercentile(double percentile) {
        assertThat(Percentiles.nearestRank(new long[] {42}, percentile)).isEqualTo(42);
    }

    @Test
    void twoSamples_theMedianIsTheSmallerOne_anythingAboveItTheLarger() {
        long[] samples = {20, 10};

        assertThat(Percentiles.nearestRank(samples, 50)).isEqualTo(10);
        assertThat(Percentiles.nearestRank(samples, 50.0001)).isEqualTo(20);
        assertThat(Percentiles.nearestRank(samples, 95)).isEqualTo(20);
        assertThat(Percentiles.nearestRank(samples, 0.0001)).isEqualTo(10);
    }

    @Test
    void hundredth_isTheMaximum_andTheSmallestPercentileTheMinimum() {
        long[] samples = oneTo(200);

        assertThat(Percentiles.nearestRank(samples, 100)).isEqualTo(200);
        assertThat(Percentiles.nearestRank(samples, 0.0001)).isEqualTo(1);
    }

    @Test
    void ranksLandExactlyOnWholeNumbers_withoutFloatingPointDrift() {
        assertThat(Percentiles.nearestRank(oneTo(200), 95)).isEqualTo(190);
        assertThat(Percentiles.nearestRank(oneTo(200), 99)).isEqualTo(198);
        assertThat(Percentiles.nearestRank(oneTo(200), 50)).isEqualTo(100);
        assertThat(Percentiles.nearestRank(oneTo(100), 95)).isEqualTo(95);
        assertThat(Percentiles.nearestRank(oneTo(20), 95)).isEqualTo(19);
        assertThat(Percentiles.nearestRank(oneTo(3), 50)).isEqualTo(2);
    }

    @Test
    void aRankJustPastAWholeNumber_roundsUpToTheNextSample() {
        assertThat(Percentiles.nearestRank(oneTo(200), 95.5)).isEqualTo(191);
        assertThat(Percentiles.nearestRank(oneTo(200), 94.9)).isEqualTo(190);
    }

    @Test
    void unsortedInput_isNeitherAProblemNorModified() {
        long[] samples = {30, 10, 50, 20, 40};
        long[] copy = samples.clone();

        assertThat(Percentiles.nearestRank(samples, 60)).isEqualTo(30);
        assertThat(samples).containsExactly(copy);
    }

    @Test
    void duplicateSamples_countOncePerOccurrence() {
        assertThat(Percentiles.nearestRank(new long[] {5, 1, 5, 5}, 25)).isEqualTo(1);
        assertThat(Percentiles.nearestRank(new long[] {5, 1, 5, 5}, 26)).isEqualTo(5);
    }

    @Test
    void summary_ofNoSamples_isRejected() {
        assertThatThrownBy(() -> LatencySummary.of(new long[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summary_ofOneSample_isThatSampleEverywhere() {
        assertThat(LatencySummary.of(new long[] {7})).isEqualTo(new LatencySummary(1, 7, 7, 7, 7));
    }

    @Test
    void summary_ofTwoHundredSamples_holdsTheNearestRankP50P95P99AndTheMaximum() {
        assertThat(LatencySummary.of(oneTo(200))).isEqualTo(new LatencySummary(200, 100, 190, 198, 200));
    }
}
