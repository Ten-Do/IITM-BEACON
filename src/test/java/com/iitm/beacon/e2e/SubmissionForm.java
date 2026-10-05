package com.iitm.beacon.e2e;

import com.iitm.beacon.testsupport.TestImages;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.FilePayload;
import java.awt.Color;
import java.util.List;
import java.util.Map;

/** Actions on the submission form (submission/form.html) shared by its e2e tests. */
final class SubmissionForm {

    private SubmissionForm() {
    }

    static Locator chip(Page page, String label) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label).setExact(true));
    }

    /** One topic's answer + photos block, by its catalog slug. */
    static Locator topic(Page page, String slug) {
        return page.locator(".submission-topic-block:has(input[type=hidden][value='" + slug + "'])");
    }

    static Locator picker(Page page, String slug) {
        return topic(page, slug).locator("[data-photo-picker]");
    }

    static void answer(Page page, String slug, String text) {
        topic(page, slug).locator("textarea").fill(text);
    }

    static void fillIdentity(Page page) {
        page.locator("#firstName").fill("Ada");
        page.locator("#lastName").fill("Lovelace");
        page.locator("#rollNumber").fill("CS22B007");
        page.locator("#admissionYear").fill("2022");
    }

    static void addPhotos(Page page, String slug, FilePayload... files) {
        picker(page, slug).locator("input[type=file]").setInputFiles(files);
    }

    /**
     * Chooses zero-filled files made up in the page itself (name, type,
     * size): sending large files from the test to the browser is slow.
     */
    static void addBlankFiles(Page page, String slug, List<Map<String, Object>> files) {
        picker(page, slug).locator("input[type=file]").evaluate("""
                (input, files) => {
                  const transfer = new DataTransfer();
                  files.forEach((file) => transfer.items.add(
                      new File([new Uint8Array(file.size)], file.name, { type: file.type })));
                  input.files = transfer.files;
                  input.dispatchEvent(new Event('change', { bubbles: true }));
                }""", files);
    }

    /** A PNG of the given size, a flat {@code background} with a {@code shape}-coloured ellipse. */
    static FilePayload png(String name, int width, int height, Color background, Color shape) {
        return new FilePayload(name, "image/png", TestImages.png(E2eData.scene(width, height, background, shape)));
    }
}
