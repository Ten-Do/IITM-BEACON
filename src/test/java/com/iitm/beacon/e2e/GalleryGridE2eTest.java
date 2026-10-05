package com.iitm.beacon.e2e;

import static com.iitm.beacon.e2e.E2eData.GOLD;
import static com.iitm.beacon.e2e.E2eData.MAROON;
import static com.iitm.beacon.e2e.E2eData.NAVY;
import static com.iitm.beacon.e2e.E2eData.OLIVE;
import static com.iitm.beacon.e2e.E2eData.SAND;
import static com.iitm.beacon.e2e.E2eData.TEAL;
import static com.iitm.beacon.e2e.E2eData.WHITE;
import static com.iitm.beacon.e2e.E2eData.photo;

import org.junit.jupiter.api.Test;

/** The gallery list's card grid: 4 columns at 1280px, 3 at 1000px, 2 at 700px, 1 at 390px. */
class GalleryGridE2eTest extends E2eTestBase {

    @Test
    void grid_fourThreeTwoOneColumns() {
        data().testimonial("Ana", "Pereira").country("PT").score(9)
                .section("academics_teaching", "The professors were approachable and expected a lot of independent"
                        + " reading, which took some getting used to.", photo(1600, 1000, NAVY, GOLD))
                .approved();
        data().testimonial("Lukas", "Brandt").score(6)
                .section("housing_accommodation", "The hostel room was basic but clean.",
                        photo(1000, 1000, OLIVE, WHITE))
                .approved();
        data().testimonial("Chloe", "Martin").country("FR").score(3)
                .section("general", "Paperwork took most of my first month; plan for it.")
                .approved();
        data().testimonial("Sofia", "Rossi").country("IT").score(10)
                .section("travel_recommend", "Hampi. Rent a bicycle and watch the sunset from Matanga Hill.",
                        photo(900, 1350, MAROON, SAND))
                .approved();
        data().testimonial("Erik", "Lindqvist").country("SE").score(0)
                .section("adapt_weather", "The heat in April was more than I could handle.",
                        photo(1200, 800, TEAL, GOLD))
                .approved();
        openPage(DevicePreset.DESKTOP);
        navigate("/gallery");

        for (int width : new int[] {1280, 1000, 700, 390}) {
            page().setViewportSize(width, 800);
            assertScreenshot("gallery-grid-" + width + "px");
        }
    }
}
