package com.iitm.beacon.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

/**
 * The admin catalog pages (decision 28) on a desktop, and the cascade a
 * catalog change has on a published article: deactivating a topic hides its
 * section, reactivating it brings the article back exactly as it was.
 */
class CatalogAdminE2eTest extends E2eTestBase {

    /** The table row of the topic with {@code slug}. */
    private Locator topicRow(String slug) {
        return page().locator("tr:has(td.catalog-slug:text-is('" + slug + "'))");
    }

    /** Clicks the topic's Active/Inactive pill and waits for the list to come back. */
    private void toggleTopic(String slug) {
        navigate("/catalog/topics");
        topicRow(slug).locator(".catalog-pill").click();
        page().locator(".catalog-notice").waitFor();
    }

    @Test
    void catalogPages_onADesktop() {
        data().approvedArticle();
        openPage(DevicePreset.DESKTOP);
        loginAsAdmin();

        navigate("/catalog/topics");
        assertFullPageScreenshot("topics");
        navigate("/catalog/achievements");
        assertFullPageScreenshot("achievements");

        navigate("/catalog/topics/new");
        assertScreenshot("topic-new");
        page().locator("#label").fill("Visa tips");
        page().locator("#slug").fill("general");
        page().locator("#guidingPrompt").fill("How did your visa application go?");
        page().locator("#displayOrder").fill("18");
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add topic")).click();
        page().locator(".catalog-error-banner, .catalog-field-error").first().waitFor();
        assertScreenshot("topic-new-duplicate-slug");

        navigate("/catalog/topics");
        topicRow("general").getByText("Edit").click();
        page().locator("#label").waitFor();
        assertScreenshot("topic-edit-general");

        navigate("/catalog/topics");
        topicRow("academics_teaching").getByText("Delete").click();
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Delete topic")).waitFor();
        assertScreenshot("topic-delete-confirm");
    }

    @Test
    void deactivatingATopic_hidesItsSectionFromTheArticle_untilItIsReactivated() {
        long id = data().approvedArticle();
        openPage(DevicePreset.DESKTOP);
        loginAsAdmin();

        navigate("/gallery/" + id);
        assertFullPageScreenshot("article");

        toggleTopic("academics_teaching");
        assertScreenshot(topicRow("academics_teaching"), "topic-row-inactive");
        navigate("/gallery/" + id);
        assertFullPageScreenshot("article-teaching-topic-hidden");

        toggleTopic("academics_teaching");
        navigate("/gallery/" + id);
        assertFullPageScreenshot("article");
    }
}
