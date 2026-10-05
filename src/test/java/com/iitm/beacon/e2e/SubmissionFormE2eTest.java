package com.iitm.beacon.e2e;

import static com.iitm.beacon.e2e.SubmissionForm.answer;
import static com.iitm.beacon.e2e.SubmissionForm.chip;
import static com.iitm.beacon.e2e.SubmissionForm.fillIdentity;
import static com.iitm.beacon.e2e.SubmissionForm.topic;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

/**
 * The submission form's own behaviour (static/js/submission-form.js): the
 * score slider's number, label and colour follow it; topic chips show and
 * hide their topics; with the country still on its placeholder the browser
 * won't submit and takes the visitor to the country field. (The form as it
 * opens — score 10, placeholder — is in {@link PagesE2eTest}.)
 */
class SubmissionFormE2eTest extends E2eTestBase {

    @Test
    void scoreSlider_topicChips_andSubmitWithTheCountryPlaceholder() {
        openPage(DevicePreset.DESKTOP);
        loginAsVisitor("form.visitor@example.com");
        Page page = page();

        page.locator("#recommendationScore").fill("0");
        assertScreenshot(page.locator(".submission-score-wrap"), "score-slider-at-0");

        chip(page, "General").click();
        chip(page, "New Friendships").click();
        topic(page, "new_friendships").waitFor();
        assertScreenshot(page.locator("section:has(.submission-chip-toggle-row)"),
                "topics-new-friendships-picked-general-unpicked");

        fillIdentity(page);
        answer(page, "new_friendships", "I met people from twelve countries.");
        page.getByLabel("I consent to IITM Beacon storing").check();
        page.locator("button[type=submit].submission-btn-primary").click();
        // The browser's "Please select an item" bubble fades in, then stays for at least 5 s.
        page.waitForTimeout(1000);
        assertScreenshot("submit-refused-country-placeholder-focused");
    }
}
