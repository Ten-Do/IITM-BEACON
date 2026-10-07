package com.iitm.beacon.e2e;

import org.junit.jupiter.api.Test;

/**
 * The homepage dashboard (decision 30): the map shaded by count, the stat
 * cards and the two lists, on a desktop and on a phone; the "more" lists
 * opened; a click on a country leading to the filtered gallery; and the
 * empty state before anything is approved.
 */
class DashboardE2eTest extends E2eTestBase {

    /**
     * Approved testimonials from eight countries with uneven counts, so that
     * the map shows several shades, the chips overflow into "+ 2 more
     * countries" and both lists into "Show all". Singapore is too small for
     * the map and shows only as a chip. The pending one must change nothing.
     */
    private void seedDashboard() {
        approved("Anna", "Becker", "DE", 9, "academics_teaching", "housing_food",
                "made_new_friends", "traveled_within_india", "enjoyed_spicy_indian_food");
        approved("Jonas", "Weber", "DE", 10, "academics_difficulty", "travel_did",
                "made_new_friends", "traveled_within_india", "explored_the_city");
        approved("Lena", "Fischer", "DE", 8, "campus_events", "general",
                "made_new_friends", "keeping_in_touch");
        approved("Paul", "Wagner", "DE", 7, "academics_style", "romance",
                "made_new_friends", "joined_club_or_sports_team");
        approved("Mia", "Schulz", "DE", 6, "practical_cost", "general",
                "traveled_within_india", "missed_home");
        approved("Camille", "Martin", "FR", 9, "academics_teaching", "oge_support",
                "made_new_friends", "enjoyed_spicy_indian_food");
        approved("Hugo", "Bernard", "FR", 5, "adapt_weather", "general",
                "missed_home", "faced_culture_shock");
        approved("Louise", "Petit", "FR", 8, "housing_accommodation", "travel_recommend",
                "traveled_within_india");
        approved("Emma", "Johnson", "US", 9, "academics_support", "career",
                "built_professional_network", "made_new_friends");
        approved("Noah", "Smith", "US", 4, "health_care", "general",
                "adjusted_to_heat");
        approved("Lucas", "Silva", "BR", 10, "reflect_again", "new_friendships",
                "made_new_friends", "keeping_in_touch");
        approved("Yui", "Tanaka", "JP", 8, "campus_clubs", "general",
                "joined_club_or_sports_team");
        approved("Ines", "Costa", "PT", 7, "travel_ease", "general",
                "explored_the_city");
        approved("Elin", "Berg", "SE", 3, "adapt_shock", "general",
                "faced_culture_shock");
        approved("Wei Ling", "Tan", "SG", 9, "academics_teaching", "general",
                "learned_local_language");
        data().testimonial("Pending", "Person").country("AU").score(0)
                .section("general", "Still waiting for review.")
                .achievements("overcame_health_challenge")
                .pending();
    }

    private void approved(String firstName, String lastName, String country, int score,
            String firstTopic, String secondTopic, String... achievements) {
        data().testimonial(firstName, lastName).country(country).score(score)
                .section(firstTopic, firstName + " wrote about " + firstTopic + ".")
                .section(secondTopic, firstName + " wrote about " + secondTopic + ".")
                .achievements(achievements)
                .approved();
    }

    @Test
    void dashboard_desktop() {
        seedDashboard();
        openPage(DevicePreset.DESKTOP);
        navigate("/");
        assertFullPageScreenshot("dashboard-desktop");
    }

    @Test
    void dashboard_everyListOpened_desktop() {
        seedDashboard();
        openPage(DevicePreset.DESKTOP);
        navigate("/");
        for (int i = page().locator("details summary").count() - 1; i >= 0; i--) {
            page().locator("details summary").nth(i).click();
        }
        assertFullPageScreenshot("dashboard-everything-open-desktop");
    }

    @Test
    void dashboard_mobile() {
        seedDashboard();
        openPage(DevicePreset.MOBILE);
        navigate("/");
        assertFullPageScreenshot("dashboard-mobile");
    }

    /** Germany: a compact outline, so the centre of its box, where the click lands, is on the country. */
    @Test
    void clickingACountryOnTheMap_opensTheGalleryFilteredByIt() {
        seedDashboard();
        openPage(DevicePreset.DESKTOP);
        navigate("/");
        page().locator("svg a[href='/gallery?country=DE']").click();
        page().waitForURL("**/gallery?country=DE");
        assertScreenshot("map-click-germany-gallery");
    }

    @Test
    void emptyDashboard_desktopAndMobile() {
        data().testimonial("Pending", "Person").country("AU").pending();
        openPage(DevicePreset.DESKTOP);
        navigate("/");
        assertScreenshot("dashboard-empty-desktop");

        openPage(DevicePreset.MOBILE);
        navigate("/");
        assertScreenshot("dashboard-empty-mobile");
    }
}
