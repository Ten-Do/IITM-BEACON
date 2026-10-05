package com.iitm.beacon.e2e;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Outcome of comparing a screenshot against its baseline, pixel by pixel.
 * Pure in-memory logic (no files, no browser) so it can be unit-tested with
 * synthetic images; {@link ScreenshotAssert} handles the file side.
 */
sealed interface ImageComparison {

    /** Colour used in {@link PixelDiff#diffImage()} to mark a differing pixel. */
    int HIGHLIGHT = 0xFFFF0000;

    /** Whether the actual image is acceptable against the baseline. */
    boolean matches();

    /**
     * Compares {@code actual} with {@code expected}. Images of different
     * sizes never match, whatever the tolerance. Otherwise a pixel differs
     * when any of its A/R/G/B channels deviates by more than {@link
     * ScreenshotTolerance#maxChannelDelta()}, and the images match when the
     * share of differing pixels is at most {@link
     * ScreenshotTolerance#maxDiffPixelRatio()}. The pixel format of the two
     * images is irrelevant; only their ARGB colours are compared.
     */
    static ImageComparison compare(BufferedImage expected, BufferedImage actual, ScreenshotTolerance tolerance) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(tolerance, "tolerance");

        int width = expected.getWidth();
        int height = expected.getHeight();
        if (width != actual.getWidth() || height != actual.getHeight()) {
            return new SizeMismatch(width, height, actual.getWidth(), actual.getHeight());
        }

        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        long differing = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int expectedPixel = expected.getRGB(x, y);
                if (maxChannelDelta(expectedPixel, actual.getRGB(x, y)) > tolerance.maxChannelDelta()) {
                    differing++;
                    diffImage.setRGB(x, y, HIGHLIGHT);
                } else {
                    diffImage.setRGB(x, y, faded(expectedPixel));
                }
            }
        }
        long total = (long) width * height;
        boolean matches = (double) differing / total <= tolerance.maxDiffPixelRatio();
        return new PixelDiff(differing, total, matches, diffImage);
    }

    private static int maxChannelDelta(int a, int b) {
        int max = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int delta = Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF));
            max = Math.max(max, delta);
        }
        return max;
    }

    /** Opaque, washed-out grey version of a pixel — context for the red highlights. */
    private static int faded(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int luma = (r * 299 + g * 587 + b * 114) / 1000;
        int grey = 255 - (255 - luma) / 4;
        return 0xFF000000 | (grey << 16) | (grey << 8) | grey;
    }

    /** The two images have different dimensions, so no pixel comparison was made. */
    record SizeMismatch(int expectedWidth, int expectedHeight, int actualWidth, int actualHeight)
            implements ImageComparison {

        @Override
        public boolean matches() {
            return false;
        }
    }

    /**
     * Same-size comparison result.
     *
     * @param diffImage same-size image: differing pixels in {@link #HIGHLIGHT}
     *     red, every other pixel a faded grey copy of the baseline
     */
    record PixelDiff(long differingPixels, long totalPixels, boolean matches, BufferedImage diffImage)
            implements ImageComparison {

        double diffRatio() {
            return (double) differingPixels / totalPixels;
        }
    }
}
