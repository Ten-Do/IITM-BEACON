package com.iitm.beacon.e2e;

import static com.iitm.beacon.e2e.E2eData.GOLD;
import static com.iitm.beacon.e2e.E2eData.MAROON;
import static com.iitm.beacon.e2e.E2eData.NAVY;
import static com.iitm.beacon.e2e.E2eData.OLIVE;
import static com.iitm.beacon.e2e.E2eData.SAND;
import static com.iitm.beacon.e2e.E2eData.TEAL;
import static com.iitm.beacon.e2e.E2eData.WHITE;
import static com.iitm.beacon.e2e.SubmissionForm.addBlankFiles;
import static com.iitm.beacon.e2e.SubmissionForm.addPhotos;
import static com.iitm.beacon.e2e.SubmissionForm.answer;
import static com.iitm.beacon.e2e.SubmissionForm.fillIdentity;
import static com.iitm.beacon.e2e.SubmissionForm.picker;
import static com.iitm.beacon.e2e.SubmissionForm.png;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The submission form's photo picker (static/js/submission-form.js): previews,
 * × to remove, a tags field per photo that stays with it, the "n of 5"
 * counter, refusals with a message (over 20 MB, not an image, over 5 per
 * topic), a named tile for what the browser can't preview, drag and drop —
 * and in edit mode the saved photos with their tags, where removing one frees
 * its slot.
 */
class SubmissionPhotoPickerE2eTest extends E2eTestBase {

    private static final String GENERAL = "general";
    private static final int MB = 1024 * 1024;

    private static final FilePayload A = png("a.png", 300, 200, NAVY, GOLD);
    private static final FilePayload B = png("b.png", 200, 300, OLIVE, WHITE);
    private static final FilePayload C = png("c.png", 240, 240, MAROON, SAND);
    private static final FilePayload D = png("d.png", 320, 180, TEAL, SAND);
    private static final FilePayload E = png("e.png", 180, 320, GOLD, NAVY);
    private static final FilePayload F = png("f.png", 260, 260, SAND, MAROON);
    private static final FilePayload G = png("g.png", 280, 210, WHITE, TEAL);

    private Page openFormWithText() {
        openPage(DevicePreset.DESKTOP);
        loginAsVisitor("picker.visitor@example.com");
        answer(page(), GENERAL, "My photos from the semester.");
        return page();
    }

    private static Locator newTile(Page page, int index) {
        return picker(page, GENERAL).locator("[data-new-photo]").nth(index);
    }

    private static Map<String, Object> blank(String name, String type, int size) {
        return Map.of("name", name, "type", type, "size", size);
    }

    private void assertPicker(String name) {
        page().mouse().move(0, 0);
        assertScreenshot(picker(page(), GENERAL), name);
    }

    @Test
    void newPhotos_previewsRemoveTagsRefusalsNamedTilesAndTheTopicLimit() {
        Page page = openFormWithText();

        addPhotos(page, GENERAL, A, B, C);
        assertPicker("three-previews");

        newTile(page, 1).getByLabel("Remove photo").click();
        newTile(page, 0).getByLabel("Photo tags").fill("alpha, sunset");
        newTile(page, 1).getByLabel("Photo tags").fill("gamma");
        // 20 MB exactly is allowed, one byte more is not; the browser can't preview the
        // zero-filled "photos" (or a HEIC file), so their tiles show the file name.
        addBlankFiles(page, GENERAL, List.of(
                blank("exactly-20mb.jpg", "image/jpeg", 20 * MB),
                blank("over-20mb.jpg", "image/jpeg", 20 * MB + 1),
                blank("notes.txt", "text/plain", 11),
                blank("IMG_0042.heic", "image/heic", 64)));
        newTile(page, 2).locator(".submission-photo-tile-fallback").waitFor();
        newTile(page, 3).locator(".submission-photo-tile-fallback").waitFor();
        assertPicker("b-removed-tags-typed-two-named-tiles-two-refusals");

        addPhotos(page, GENERAL, D, E);
        assertPicker("five-of-five-sixth-refused");
    }

    @Test
    void droppedPhotos_areAddedLikeChosenOnes() {
        Page page = openFormWithText();
        Locator dropZone = picker(page, GENERAL).locator("label.submission-photo-add");

        dropZone.dispatchEvent("dragover");
        assertScreenshot(dropZone, "drop-zone-while-dragging-over");

        dropZone.evaluate("""
                async (zone) => {
                  const canvas = document.createElement('canvas');
                  canvas.width = 120;
                  canvas.height = 90;
                  canvas.getContext('2d').fillStyle = '#22345e';
                  canvas.getContext('2d').fillRect(0, 0, 120, 90);
                  const png = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
                  const transfer = new DataTransfer();
                  transfer.items.add(new File([png], 'dropped.png', { type: 'image/png' }));
                  transfer.items.add(new File(['plain text'], 'dropped.txt', { type: 'text/plain' }));
                  zone.dispatchEvent(
                      new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true }));
                }""");
        assertPicker("after-dropping-a-photo-and-a-text-file");
    }

    @Test
    void savedPhotos_inEditModeWithTheirTags_removingOneFreesItsSlot() {
        Page page = openFormWithText();
        fillIdentity(page);
        page.locator("#countryCode").selectOption("PT");
        page.getByLabel("I consent to IITM Beacon storing").check();
        addPhotos(page, GENERAL, A, B, C);
        newTile(page, 0).getByLabel("Photo tags").fill("alpha, sunset");
        newTile(page, 1).getByLabel("Photo tags").fill("beta");
        newTile(page, 2).getByLabel("Photo tags").fill("gamma");
        newTile(page, 1).getByLabel("Remove photo").click();
        addPhotos(page, GENERAL, D, E, F);
        newTile(page, 4).getByLabel("Photo tags").fill("zeta");
        page.locator("button[type=submit].submission-btn-primary").click();
        page.waitForURL("**/submissions/confirmation");

        navigate("/submissions/form");
        assertPicker("edit-mode-saved-photos-with-their-tags");

        picker(page, GENERAL).locator("[data-saved-photo]").nth(1).getByLabel("Remove photo").click();
        addPhotos(page, GENERAL, G);
        assertPicker("edit-mode-one-saved-removed-one-new-added");
    }
}
