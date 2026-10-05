package com.iitm.beacon.e2e;

import org.junit.jupiter.api.Test;

/** Smoke test of the e2e setup: the empty public gallery on a desktop and on a phone. */
class GallerySmokeE2eTest extends E2eTestBase {

    @Test
    void emptyGallery_desktop() {
        openPage(DevicePreset.DESKTOP);
        navigate("/gallery");
        assertScreenshot("gallery-desktop");
    }

    @Test
    void emptyGallery_mobile() {
        openPage(DevicePreset.MOBILE);
        navigate("/gallery");
        assertScreenshot("gallery-mobile");
    }
}
