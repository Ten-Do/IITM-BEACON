package com.iitm.beacon.e2e;

import com.microsoft.playwright.Route;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * "Reveal contact info" on the article (static/js/contact-reveal.js): a
 * spinner while it loads, then the public contacts (never the private one)
 * in place of the button, the page where it was; a failed request says so
 * and can be retried.
 */
class ContactRevealE2eTest extends E2eTestBase {

    private static final String CONTACT_REQUESTS = "**/gallery/*/contact";
    private static final String REVEAL = "button.gallery-reveal-btn";
    private static final String REVEALED = "[data-contact-card][role=region]";

    @Test
    void phone_spinnerWhileLoading_thenTheContactsInPlace() {
        long id = data().approvedArticle();
        openPage(DevicePreset.MOBILE);
        navigate("/gallery/" + id);
        page().locator("[data-contact-card]").scrollIntoViewIfNeeded();
        AtomicReference<Route> held = new AtomicReference<>();
        page().route(CONTACT_REQUESTS, held::set);

        page().locator(REVEAL).tap();
        page().waitForCondition(() -> held.get() != null);
        assertScreenshot("loading-phone");

        held.get().resume();
        page().locator(REVEALED).waitFor();
        assertScreenshot("revealed-phone");
    }

    @Test
    void desktop_aFailedRequestSaysSo_andTheRetryReveals() {
        long id = data().approvedArticle();
        openPage(DevicePreset.DESKTOP);
        navigate("/gallery/" + id);
        AtomicBoolean failed = new AtomicBoolean();
        page().route(CONTACT_REQUESTS, route -> {
            if (failed.compareAndSet(false, true)) {
                route.fulfill(new Route.FulfillOptions().setStatus(500));
            } else {
                route.resume();
            }
        });

        page().locator(REVEAL).click();
        page().locator("[data-contact-reveal-error]").waitFor();
        assertScreenshot("failed-desktop");

        page().locator(REVEAL).click();
        page().locator(REVEALED).waitFor();
        assertScreenshot("revealed-after-retry-desktop");
    }
}
