package com.iitm.beacon.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;

/**
 * The fullscreen photo viewer (static/js/photo-viewer.js, PhotoSwipe) on the
 * article and in the moderation queue. The article's photos, in order: a
 * landscape and a portrait one (Academics / Teaching Quality, tagged), then
 * a square one (General) — the arrows must sit in the same place on all
 * three screenshots.
 */
class PhotoViewerE2eTest extends E2eTestBase {

    private static final String PHOTO = "a[data-photo-viewer-item]";

    /**
     * Synchronisation only: the viewer shows photo {@code counter} ("i / n"),
     * its full image has loaded and the slide strip has stopped moving (the
     * same position on three polls in a row, one per animation frame).
     */
    private static final String SETTLED_JS = """
            (counter) => {
              const viewer = document.querySelector('.pswp.pswp--open');
              const shown = viewer && viewer.querySelector('.photo-viewer-counter');
              const slide = viewer && viewer.querySelector('.pswp__item[aria-hidden="false"]');
              const image = slide && slide.querySelector('img.pswp__img:not(.pswp__img--placeholder)');
              if (!shown || shown.textContent !== counter || !image || !image.complete) {
                window.e2eStill = 0;
                return false;
              }
              const position = getComputedStyle(viewer.querySelector('.pswp__container')).transform;
              window.e2eStill = position === window.e2ePosition ? (window.e2eStill || 0) + 1 : 0;
              window.e2ePosition = position;
              return window.e2eStill >= 2;
            }""";

    private void waitForViewer(String counter) {
        page().evaluate("() => { window.e2eStill = 0; window.e2ePosition = null; }");
        page().waitForFunction(SETTLED_JS, counter);
    }

    private void pressAndWait(String key, String counter) {
        page().keyboard().press(key);
        waitForViewer(counter);
    }

    @Test
    void article_desktop_opensOnTheClickedPhoto_arrowKeysPageThroughAndWrap_escapeCloses() {
        long id = data().approvedArticle();
        openPage(DevicePreset.DESKTOP);
        navigate("/gallery/" + id);

        page().locator(PHOTO).first().click();
        waitForViewer("1 / 3");
        assertScreenshot("landscape-desktop");
        pressAndWait("ArrowRight", "2 / 3");
        assertScreenshot("portrait-desktop");
        pressAndWait("ArrowRight", "3 / 3");
        assertScreenshot("square-desktop");
        pressAndWait("ArrowRight", "1 / 3");
        assertScreenshot("landscape-desktop");

        page().keyboard().press("Escape");
        page().locator(".pswp").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
        assertScreenshot("article-after-closing-desktop");
    }

    @Test
    void article_phone_opensOnTheTappedPhoto_andSwipesBothWays() {
        long id = data().approvedArticle();
        openPage(DevicePreset.MOBILE);
        navigate("/gallery/" + id);

        page().locator(PHOTO).first().tap();
        waitForViewer("1 / 3");
        assertScreenshot("landscape-phone");
        swipe(330, 60);
        waitForViewer("2 / 3");
        assertScreenshot("portrait-phone");
        swipe(330, 60);
        waitForViewer("3 / 3");
        assertScreenshot("square-phone");
        swipe(60, 330);
        waitForViewer("2 / 3");
        assertScreenshot("portrait-phone");
    }

    @Test
    void queue_eachTestimonialIsItsOwnGallery() {
        data().twoPendingTestimonials();
        openPage(DevicePreset.DESKTOP);
        loginAsAdmin();
        assertFullPageScreenshot("queue-desktop");

        page().locator(".moderation-queue-item").first().locator(PHOTO).first().click();
        waitForViewer("1 / 2");
        pressAndWait("ArrowRight", "2 / 2");
        assertScreenshot("queue-first-testimonial-photo-2-of-2");
        // Past its last photo it wraps to its own first one, never to the next testimonial's.
        pressAndWait("ArrowRight", "1 / 2");
        assertScreenshot("queue-first-testimonial-photo-1-of-2");
    }

    /** A one-finger horizontal swipe at y=150 (Playwright has no swipe: raw touch events through CDP). */
    private void swipe(int fromX, int toX) {
        CDPSession touch = page().context().newCDPSession(page());
        touch.send("Input.dispatchTouchEvent", touchEvent("touchStart", fromX));
        for (int step = 1; step <= 8; step++) {
            touch.send("Input.dispatchTouchEvent", touchEvent("touchMove", fromX + (toX - fromX) * step / 8));
            page().waitForTimeout(16);
        }
        JsonObject end = new JsonObject();
        end.addProperty("type", "touchEnd");
        end.add("touchPoints", new JsonArray());
        touch.send("Input.dispatchTouchEvent", end);
        touch.detach();
    }

    private static JsonObject touchEvent(String type, int x) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", 150);
        JsonArray points = new JsonArray();
        points.add(point);
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.add("touchPoints", points);
        return event;
    }
}
