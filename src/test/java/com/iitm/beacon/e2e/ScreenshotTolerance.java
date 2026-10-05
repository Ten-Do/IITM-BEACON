package com.iitm.beacon.e2e;

/**
 * How far a screenshot may deviate from its baseline and still match.
 *
 * @param maxChannelDelta largest allowed absolute difference (0-255) in any
 *     single ARGB channel before a pixel counts as "differing"
 * @param maxDiffPixelRatio largest allowed share (0.0-1.0) of differing
 *     pixels in the whole image
 */
public record ScreenshotTolerance(int maxChannelDelta, double maxDiffPixelRatio) {

    /** Pixel-perfect: any change in any channel fails. */
    public static final ScreenshotTolerance EXACT = new ScreenshotTolerance(0, 0.0);

    /**
     * Absorbs anti-aliasing noise (a few units per channel on a handful of
     * pixels) but still catches a moved element, a changed colour or a
     * one-word text change: at most 0.1% of pixels may differ by more than
     * 8/255 per channel.
     */
    public static final ScreenshotTolerance DEFAULT = new ScreenshotTolerance(8, 0.001);

    public ScreenshotTolerance {
        if (maxChannelDelta < 0 || maxChannelDelta > 255) {
            throw new IllegalArgumentException("maxChannelDelta must be within 0..255, got " + maxChannelDelta);
        }
        if (!(maxDiffPixelRatio >= 0.0 && maxDiffPixelRatio <= 1.0)) {
            throw new IllegalArgumentException("maxDiffPixelRatio must be within 0.0..1.0, got " + maxDiffPixelRatio);
        }
    }
}
