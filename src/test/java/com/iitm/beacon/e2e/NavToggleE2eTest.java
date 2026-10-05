package com.iitm.beacon.e2e;

import org.junit.jupiter.api.Test;

/**
 * The header's burger menu (static/js/nav-toggle.js): at 720px and narrower
 * the nav collapses behind it; it opens on a tap and closes on Escape (focus
 * back on the burger) or on a tap outside it.
 */
class NavToggleE2eTest extends E2eTestBase {

    private static final String BURGER = "button[data-nav-toggle]";

    @Test
    void visitorMenu_onAPhone_opens_andClosesOnEscapeAndOnATapOutside() {
        openPage(DevicePreset.MOBILE);
        navigate("/gallery");
        assertScreenshot("visitor-menu-closed");

        page().locator(BURGER).tap();
        assertScreenshot("visitor-menu-open");
        page().keyboard().press("Escape");
        assertScreenshot("visitor-menu-closed-by-escape-focus-on-the-burger");

        page().locator(BURGER).tap();
        page().touchscreen().tap(195, 700);
        assertScreenshot("visitor-menu-closed");
    }

    @Test
    void adminMenu_onAPhone_open() {
        openPage(DevicePreset.MOBILE);
        loginAsAdmin();

        page().locator(BURGER).tap();
        assertScreenshot("admin-menu-open");
    }

    @Test
    void header_normalNavAt721px_burgerAt720px() {
        openPage(DevicePreset.DESKTOP);
        navigate("/gallery");

        page().setViewportSize(721, 800);
        assertScreenshot(page().locator("header"), "header-721px");
        page().setViewportSize(720, 800);
        assertScreenshot(page().locator("header"), "header-720px");
    }
}
