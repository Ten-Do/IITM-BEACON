package com.iitm.beacon.e2e;

import org.junit.jupiter.api.Test;

/**
 * An expired session on a page that needs a login (static/js/session-check.js):
 * returning to the tab reloads through the login page, and logging in comes
 * back to the page the user was on — once. The session is expired by
 * deleting the cookies; headless pages are always visible, so "returning to
 * the tab" is a dispatched {@code visibilitychange}.
 */
class SessionExpiryE2eTest extends E2eTestBase {

    private void expireTheSessionAndReturnToTheTab() {
        page().context().clearCookies();
        page().evaluate("() => document.dispatchEvent(new Event('visibilitychange'))");
    }

    @Test
    void admin_onPage2OfTheQueue_logsInAgainAndIsBackOnPage2_thenANewLoginStartsOnPage1() {
        for (int i = 1; i <= 21; i++) {
            data().testimonial("Pending", "Number" + i).section("general", "Waiting for review.").pending();
        }
        openPage(DevicePreset.DESKTOP);
        loginAsAdmin();
        navigate("/moderation/queue?page=1");

        expireTheSessionAndReturnToTheTab();
        page().waitForURL("**/admin/login");
        assertScreenshot("admin-login-after-the-session-expired");

        loginAsAdmin();
        assertScreenshot("admin-back-on-queue-page-2");

        loginAsAdmin();
        assertScreenshot("admin-next-login-on-queue-page-1");
    }

    @Test
    void visitor_onTheConfirmationPage_logsInAgainAndIsBackThere() {
        openPage(DevicePreset.DESKTOP);
        loginAsVisitor("returning.visitor@example.com");
        navigate("/submissions/confirmation");

        expireTheSessionAndReturnToTheTab();
        page().waitForURL("**/submissions/login");
        assertScreenshot("visitor-login-after-the-session-expired");

        loginAsVisitor("returning.visitor@example.com");
        assertScreenshot("visitor-back-on-the-confirmation-page");
    }
}
