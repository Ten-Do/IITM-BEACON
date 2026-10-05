package com.iitm.beacon.e2e;

import static com.iitm.beacon.e2e.E2eData.GOLD;
import static com.iitm.beacon.e2e.E2eData.NAVY;
import static com.iitm.beacon.e2e.SubmissionForm.addPhotos;
import static com.iitm.beacon.e2e.SubmissionForm.answer;
import static com.iitm.beacon.e2e.SubmissionForm.chip;
import static com.iitm.beacon.e2e.SubmissionForm.picker;
import static com.iitm.beacon.e2e.SubmissionForm.png;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The photo picker's limit for the whole testimonial, counted across topics
 * — lowered to 8 here (its own Spring context) so reaching it takes nine
 * photos, not fifty-one.
 */
@TestPropertySource(properties = "beacon.storage.max-photos-per-testimonial=8")
class SubmissionPhotoTotalLimitE2eTest extends E2eTestBase {

    private static FilePayload[] photos(String prefix, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> png(prefix + i + ".png", 160, 120, NAVY, GOLD))
                .toArray(FilePayload[]::new);
    }

    @Test
    void fiveInOneTopic_thenOnlyThreeMoreFitInAnother() {
        openPage(DevicePreset.DESKTOP);
        loginAsVisitor("total.limit@example.com");
        Page page = page();
        chip(page, "New Friendships").click();
        answer(page, "general", "General text.");
        answer(page, "new_friendships", "Friendship text.");

        addPhotos(page, "general", photos("g", 5));
        addPhotos(page, "new_friendships", photos("n", 4));

        page.mouse().move(0, 0);
        assertScreenshot(picker(page, "new_friendships"), "second-topic-stops-at-8-in-total");
    }
}
