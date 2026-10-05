package com.iitm.beacon.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure pixel-comparison logic behind {@link ScreenshotAssert} — synthetic
 * {@link BufferedImage}s only, no browser, no files. Plain unit test (not
 * tagged {@code e2e}), so it runs in the default {@code mvn test}.
 */
class ImageComparisonTest {

    private static final int GREY = 0xFF808080;
    private static final int RED = 0xFFFF0000;

    private static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static BufferedImage withPixels(BufferedImage source, int count, int argb) {
        BufferedImage copy = solid(source.getWidth(), source.getHeight(), 0);
        copy.getGraphics().drawImage(source, 0, 0, null);
        for (int i = 0; i < count; i++) {
            copy.setRGB(i % source.getWidth(), i / source.getWidth(), argb);
        }
        return copy;
    }

    private static ImageComparison.PixelDiff pixelDiff(ImageComparison comparison) {
        assertThat(comparison).isInstanceOf(ImageComparison.PixelDiff.class);
        return (ImageComparison.PixelDiff) comparison;
    }

    // -- identical / tolerance boundaries --

    @Test
    void identicalImages_match_withNoDifferingPixels() {
        ImageComparison.PixelDiff diff = pixelDiff(
                ImageComparison.compare(solid(10, 10, GREY), solid(10, 10, GREY), ScreenshotTolerance.EXACT));

        assertThat(diff.matches()).isTrue();
        assertThat(diff.differingPixels()).isZero();
        assertThat(diff.totalPixels()).isEqualTo(100);
        assertThat(diff.diffRatio()).isZero();
    }

    @Test
    void channelDeltaEqualToTolerance_isNotADifferingPixel() {
        BufferedImage actual = withPixels(solid(10, 10, GREY), 1, 0xFF888888); // +8 on R, G and B

        ImageComparison.PixelDiff diff = pixelDiff(
                ImageComparison.compare(solid(10, 10, GREY), actual, new ScreenshotTolerance(8, 0.0)));

        assertThat(diff.differingPixels()).isZero();
        assertThat(diff.matches()).isTrue();
    }

    @Test
    void channelDeltaOneAboveTolerance_isADifferingPixel() {
        BufferedImage actual = withPixels(solid(10, 10, GREY), 1, 0xFF898080); // +9 on R only

        ImageComparison.PixelDiff diff = pixelDiff(
                ImageComparison.compare(solid(10, 10, GREY), actual, new ScreenshotTolerance(8, 0.0)));

        assertThat(diff.differingPixels()).isEqualTo(1);
        assertThat(diff.matches()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0xFF818080, 0xFF808180, 0xFF808081, 0xFE808080})
    void aOneUnitChangeInAnySingleChannelIncludingAlpha_failsAnExactComparison(int changedPixel) {
        BufferedImage actual = withPixels(solid(4, 4, GREY), 1, changedPixel);

        ImageComparison.PixelDiff diff = pixelDiff(
                ImageComparison.compare(solid(4, 4, GREY), actual, ScreenshotTolerance.EXACT));

        assertThat(diff.differingPixels()).isEqualTo(1);
        assertThat(diff.matches()).isFalse();
    }

    @Test
    void maximumChannelTolerance_treatsEvenOppositeColoursAsEqual() {
        ImageComparison.PixelDiff diff = pixelDiff(ImageComparison.compare(
                solid(3, 3, 0xFF000000), solid(3, 3, 0x00FFFFFF), new ScreenshotTolerance(255, 0.0)));

        assertThat(diff.differingPixels()).isZero();
        assertThat(diff.matches()).isTrue();
    }

    @Test
    void diffRatioExactlyAtTheMaximum_matches() {
        BufferedImage actual = withPixels(solid(10, 10, GREY), 1, RED); // 1 of 100 = 0.01

        ImageComparison.PixelDiff diff = pixelDiff(
                ImageComparison.compare(solid(10, 10, GREY), actual, new ScreenshotTolerance(0, 0.01)));

        assertThat(diff.diffRatio()).isEqualTo(0.01);
        assertThat(diff.matches()).isTrue();
    }

    @Test
    void diffRatioOnePixelAboveTheMaximum_doesNotMatch() {
        BufferedImage actual = withPixels(solid(10, 10, GREY), 2, RED); // 2 of 100 = 0.02

        ImageComparison.PixelDiff diff = pixelDiff(
                ImageComparison.compare(solid(10, 10, GREY), actual, new ScreenshotTolerance(0, 0.01)));

        assertThat(diff.differingPixels()).isEqualTo(2);
        assertThat(diff.diffRatio()).isEqualTo(0.02);
        assertThat(diff.matches()).isFalse();
    }

    @Test
    void singlePixelImages_fullyDifferent_matchOnlyWhenTheWholeImageMayDiffer() {
        BufferedImage expected = solid(1, 1, GREY);
        BufferedImage actual = solid(1, 1, RED);

        assertThat(ImageComparison.compare(expected, actual, new ScreenshotTolerance(0, 1.0)).matches()).isTrue();
        assertThat(ImageComparison.compare(expected, actual, new ScreenshotTolerance(0, 0.99)).matches()).isFalse();
    }

    @Test
    void sameColoursInDifferentImageTypes_match() {
        BufferedImage rgb = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
        BufferedImage abgr = new BufferedImage(5, 5, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                rgb.setRGB(x, y, 0xFF123456);
                abgr.setRGB(x, y, 0xFF123456);
            }
        }

        assertThat(ImageComparison.compare(rgb, abgr, ScreenshotTolerance.EXACT).matches()).isTrue();
    }

    // -- size mismatch --

    @Test
    void differentWidth_isASizeMismatch_carryingBothSizes() {
        ImageComparison comparison =
                ImageComparison.compare(solid(10, 8, GREY), solid(11, 8, GREY), ScreenshotTolerance.DEFAULT);

        assertThat(comparison.matches()).isFalse();
        assertThat(comparison).isEqualTo(new ImageComparison.SizeMismatch(10, 8, 11, 8));
    }

    @Test
    void differentHeightOnly_isASizeMismatch() {
        ImageComparison comparison =
                ImageComparison.compare(solid(10, 8, GREY), solid(10, 7, GREY), new ScreenshotTolerance(255, 1.0));

        assertThat(comparison).isEqualTo(new ImageComparison.SizeMismatch(10, 8, 10, 7));
        assertThat(comparison.matches()).isFalse();
    }

    // -- highlighted diff image --

    @Test
    void diffImage_hasTheSameSize_marksDifferingPixelsRed_andFadesTheRestToGrey() {
        BufferedImage expected = solid(4, 3, 0xFF0000FF);
        BufferedImage actual = withPixels(expected, 1, 0xFF00FF00); // pixel (0,0) changed

        BufferedImage highlight = pixelDiff(
                ImageComparison.compare(expected, actual, ScreenshotTolerance.EXACT)).diffImage();

        assertThat(highlight.getWidth()).isEqualTo(4);
        assertThat(highlight.getHeight()).isEqualTo(3);
        assertThat(highlight.getRGB(0, 0)).isEqualTo(RED);
        int unchanged = highlight.getRGB(3, 2);
        int r = (unchanged >> 16) & 0xFF;
        int g = (unchanged >> 8) & 0xFF;
        int b = unchanged & 0xFF;
        assertThat(unchanged).isNotEqualTo(RED);
        assertThat(r).isEqualTo(g).isEqualTo(b);
    }

    @Test
    void diffImage_ofAPixelBelowTheChannelTolerance_isNotMarkedRed() {
        BufferedImage expected = solid(2, 2, GREY);
        BufferedImage actual = withPixels(expected, 1, 0xFF828282);

        BufferedImage highlight = pixelDiff(
                ImageComparison.compare(expected, actual, new ScreenshotTolerance(2, 0.0))).diffImage();

        assertThat(highlight.getRGB(0, 0)).isNotEqualTo(RED);
    }

    // -- null input --

    @Test
    void nullArguments_areRejected() {
        BufferedImage image = solid(1, 1, GREY);

        assertThatNullPointerException().isThrownBy(
                () -> ImageComparison.compare(null, image, ScreenshotTolerance.EXACT));
        assertThatNullPointerException().isThrownBy(
                () -> ImageComparison.compare(image, null, ScreenshotTolerance.EXACT));
        assertThatNullPointerException().isThrownBy(() -> ImageComparison.compare(image, image, null));
    }
}
