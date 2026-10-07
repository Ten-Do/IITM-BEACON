package com.iitm.beacon.e2e;

import static com.iitm.beacon.e2e.E2eData.GOLD;
import static com.iitm.beacon.e2e.E2eData.NAVY;
import static com.iitm.beacon.e2e.E2eData.OLIVE;
import static com.iitm.beacon.e2e.E2eData.WHITE;
import static com.iitm.beacon.e2e.E2eData.photo;

import org.junit.jupiter.api.Test;

/**
 * Every page on a phone, whole: at 390px and again at 375px, where anything
 * too wide would show as a wider screenshot. And the short pages on a
 * desktop, where the sand background must reach the bottom of the screen.
 */
class PagesE2eTest extends E2eTestBase {

    private static final String VISITOR = "phone.visitor@example.com";

    /** Opens {@code path} at 390px and takes the whole page, then again at 375px. */
    private void phoneScreenshots(String path, String name) {
        page().setViewportSize(390, 844);
        navigate(path);
        assertFullPageScreenshot(name + "-390px");
        page().setViewportSize(375, 812);
        assertFullPageScreenshot(name + "-375px");
    }

    @Test
    void publicPages_onAPhone() {
        long id = data().approvedArticle();
        openPage(DevicePreset.MOBILE);

        phoneScreenshots("/gallery", "gallery-list");
        phoneScreenshots("/gallery/" + id, "gallery-article");
        phoneScreenshots("/gallery/424242", "testimonial-not-found");
        phoneScreenshots("/submissions/login", "visitor-login");
        phoneScreenshots("/submissions/login/code?email=" + VISITOR, "visitor-login-code");
        phoneScreenshots("/admin/login", "admin-login");
        phoneScreenshots("/admin/login/code?email=" + adminEmail(), "admin-login-code");
    }

    @Test
    void visitorPages_onAPhone() {
        openPage(DevicePreset.MOBILE);
        loginAsVisitor(VISITOR);

        phoneScreenshots("/submissions/form", "submission-form-create");
        phoneScreenshots("/submissions/confirmation", "submission-confirmation");

        data().testimonial("Phone", "Visitor").email(VISITOR).country("BR").score(7)
                .section("general", "Chennai taught me patience and a love for filter coffee.",
                        photo(1200, 800, NAVY, GOLD, "coffee", "marina beach"), photo(800, 1200, OLIVE, WHITE))
                .contact("email", VISITOR, true)
                .contact("instagram", "@phone_visitor", false)
                .achievements("made_new_friends", "explored_the_city")
                .pending();
        phoneScreenshots("/submissions/form", "submission-form-edit");
    }

    @Test
    void adminPages_onAPhone() {
        openPage(DevicePreset.MOBILE);
        loginAsAdmin();
        phoneScreenshots("/moderation/queue", "moderation-queue-empty");

        data().twoPendingTestimonials();
        phoneScreenshots("/moderation/queue", "moderation-queue");

        phoneScreenshots("/catalog/topics", "catalog-topics");
        phoneScreenshots("/catalog/achievements", "catalog-achievements");
        phoneScreenshots("/catalog/topics/new", "catalog-topic-new");
    }

    @Test
    void shortPages_onADesktop() {
        openPage(DevicePreset.DESKTOP);
        String[][] pages = {
            {"/gallery/424242", "testimonial-not-found"},
            {"/submissions/login", "visitor-login"},
            {"/submissions/login/code?email=" + VISITOR, "visitor-login-code"},
            {"/admin/login", "admin-login"},
            {"/admin/login/code?email=" + adminEmail(), "admin-login-code"},
        };
        for (String[] shortPage : pages) {
            navigate(shortPage[0]);
            assertScreenshot(shortPage[1] + "-desktop");
        }

        loginAsVisitor(VISITOR);
        navigate("/submissions/confirmation");
        assertScreenshot("submission-confirmation-desktop");
        loginAsAdmin();
        assertScreenshot("moderation-queue-empty-desktop");
    }
}
