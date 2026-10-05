package com.iitm.beacon.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plain unit test (no browser, not tagged {@code e2e}) — runs in the default
 * {@code mvn test}.
 */
class ScreenshotToleranceTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 254, 255})
    void channelDelta_withinZeroTo255_isAccepted(int delta) {
        assertThat(new ScreenshotTolerance(delta, 0.0).maxChannelDelta()).isEqualTo(delta);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 256, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void channelDelta_outsideZeroTo255_isRejected(int delta) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScreenshotTolerance(delta, 0.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.0001, 0.5, 1.0})
    void ratio_withinZeroToOne_isAccepted(double ratio) {
        assertThat(new ScreenshotTolerance(0, ratio).maxDiffPixelRatio()).isEqualTo(ratio);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0001, 1.0001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void ratio_outsideZeroToOneOrNaN_isRejected(double ratio) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScreenshotTolerance(0, ratio));
    }

    @Test
    void exact_allowsNoDifferenceAtAll() {
        assertThat(ScreenshotTolerance.EXACT.maxChannelDelta()).isZero();
        assertThat(ScreenshotTolerance.EXACT.maxDiffPixelRatio()).isZero();
    }

    @Test
    void defaultTolerance_isLooserThanExactButStillStrict() {
        assertThat(ScreenshotTolerance.DEFAULT.maxChannelDelta()).isBetween(1, 32);
        assertThat(ScreenshotTolerance.DEFAULT.maxDiffPixelRatio()).isGreaterThan(0.0).isLessThanOrEqualTo(0.01);
    }
}
