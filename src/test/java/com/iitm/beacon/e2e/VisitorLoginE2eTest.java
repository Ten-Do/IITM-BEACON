package com.iitm.beacon.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The visitor's two-step login in use (its pages as they open are in {@link
 * PagesE2eTest}): a code typed in lowercase shows in upper case and is
 * accepted; a double click on "Verify" logs in, instead of sending the
 * one-time code twice and failing on the second (static/js/form-submit.js);
 * "Send code" shows it is busy while its request is on its way.
 */
class VisitorLoginE2eTest extends E2eTestBase {

    private static final String VISITOR = "login.visitor@example.com";

    private Locator button(String name) {
        return page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name));
    }

    @Test
    void codeTypedInLowercase_showsInUpperCase_andLogsIn() {
        openPage(DevicePreset.DESKTOP);
        requestVisitorLoginCode(VISITOR);
        Locator code = page().locator("#code");

        // The mailed code is random; a fixed one keeps this screenshot the same on every run.
        code.fill("k7m2qx");
        assertScreenshot(code, "code-typed-in-lowercase");

        code.fill(mailedLoginCode(VISITOR).toLowerCase(Locale.ROOT));
        button("Verify").click();
        page().waitForURL("**/submissions/form");
        assertScreenshot("logged-in-on-the-submission-form");
    }

    @Test
    void doubleClickOnVerify_logsIn() {
        openPage(DevicePreset.DESKTOP);
        requestVisitorLoginCode(VISITOR);
        page().locator("#code").fill(mailedLoginCode(VISITOR));

        button("Verify").dblclick();
        page().waitForURL("**/submissions/form");
        assertScreenshot("logged-in-on-the-submission-form");
    }

    /** The request is held (not answered) until the screenshot is taken, then let through. */
    @Test
    void sendCode_isBusyWhileItsRequestIsOnItsWay() {
        openPage(DevicePreset.DESKTOP);
        navigate("/submissions/login");
        AtomicReference<Route> held = new AtomicReference<>();
        page().route(url("/submissions/login"), held::set);
        page().locator("#email").fill(VISITOR);

        assertScreenshotWhileNavigating(button("Send code"), "send-code-busy", () -> {
            // Without noWaitAfter the click would wait for the next page, which the held request never brings.
            button("Send code").click(new Locator.ClickOptions().setNoWaitAfter(true));
            page().waitForCondition(() -> held.get() != null);
        });

        held.get().resume();
        page().waitForURL("**/submissions/login/code");
    }
}
