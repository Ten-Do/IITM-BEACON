package com.iitm.beacon.e2e;

import org.junit.jupiter.api.Test;

/**
 * {@link E2eTestBase}'s screenshot capture on a static fixture, independent
 * of the app's UI: an element screenshot is exactly the element's box, and a
 * full-page one covers the whole scrollable page.
 */
class E2eTestBaseE2eTest extends E2eTestBase {

    /** A 120x80 box followed by 2000px of content, with the app's own viewport meta. */
    private static final String FIXTURE = "<meta name='viewport' content='width=device-width, initial-scale=1'>"
            + "<style>body { margin: 0; background: #fff; }"
            + " #box { width: 120px; height: 80px; background: #b0132b; }</style>"
            + "<div id='box'></div>"
            + "<div style='height: 1000px; background: #eeeeee'></div>"
            + "<div style='height: 1000px; background: #cccccc'></div>";

    @Test
    void elementScreenshot() {
        openPage(DevicePreset.DESKTOP).setContent(FIXTURE);
        assertScreenshot(page().locator("#box"), "fixture-box");
    }

    @Test
    void fullPageScreenshot_onAPhone() {
        openPage(DevicePreset.MOBILE).setContent(FIXTURE);
        assertFullPageScreenshot("fixture-full-page-mobile");
    }
}
