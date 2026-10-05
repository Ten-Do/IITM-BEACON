package com.iitm.beacon.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.ReducedMotion;

/**
 * Browser-context presets for e2e tests. Device scale factor is pinned to 1
 * so screenshots are exactly viewport-sized (small PNGs, stable baselines);
 * locale, time zone and colour scheme are pinned so nothing depends on the
 * machine running the tests. Motion is reduced: script-driven animations
 * (the photo viewer's open/close zoom, which also delays its keyboard
 * handling) are skipped, so nothing is caught mid-animation.
 */
public enum DevicePreset {

    /** Laptop-sized desktop browser with a mouse. */
    DESKTOP(1280, 800, false),

    /** Phone-sized touch browser (iPhone 12-15 class viewport). */
    MOBILE(390, 844, true);

    private final int width;
    private final int height;
    private final boolean mobile;

    DevicePreset(int width, int height, boolean mobile) {
        this.width = width;
        this.height = height;
        this.mobile = mobile;
    }

    Browser.NewContextOptions contextOptions() {
        return new Browser.NewContextOptions()
                .setViewportSize(width, height)
                .setDeviceScaleFactor(1)
                .setIsMobile(mobile)
                .setHasTouch(mobile)
                .setLocale("en-US")
                .setTimezoneId("UTC")
                .setColorScheme(ColorScheme.LIGHT)
                .setReducedMotion(ReducedMotion.REDUCE);
    }
}
