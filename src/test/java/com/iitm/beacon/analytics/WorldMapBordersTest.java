package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.testsupport.SvgPaths;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

/**
 * Where the Russia/Ukraine border runs in the shipped {@code analytics/world-map.json}:
 * the Autonomous Republic of Crimea, Sevastopol and the Donetsk, Luhansk, Zaporizhzhia
 * and Kherson oblasts (their full admin-1 boundaries) are drawn inside the RU outline,
 * the rest of Ukraine inside the UA outline (see {@code world-map.LICENSE.txt}).
 *
 * <p>Control points are lon/lat (in the comment next to each) projected once into the
 * map's SVG coordinates with jsvectormap's own projection for this map (Miller,
 * central meridian 11.5, the map's inset bbox scaled to 900 x 440.71). Points near the
 * new border check that it runs between the right neighbours.
 */
class WorldMapBordersTest {

    private static Path2D russia;
    private static Path2D ukraine;

    @BeforeAll
    static void loadShippedOutlines() {
        WorldMap map = new WorldMap(new ObjectMapper(), new ClassPathResource("analytics/world-map.json"));
        russia = SvgPaths.parse(pathOf(map, "RU"));
        ukraine = SvgPaths.parse(pathOf(map, "UA"));
    }

    private static String pathOf(WorldMap map, String code) {
        return map.regions().stream()
                .filter(region -> region.code().equals(code))
                .findFirst()
                .orElseThrow()
                .path();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "Simferopol, 506.35, 164.12", // 34.1024 E, 44.9521 N
        // The city centre (33.5224 E, 44.6166 N) is on the coast, which the simplified
        // outline draws just inland of it; this point is in the city's territory, east of Inkerman.
        "Sevastopol, 505.27, 165.15", // 33.6742 E, 44.6177 N
        "Donetsk, 515.61, 154.49", // 37.8028 E, 48.0159 N
        "Luhansk, 519.38, 152.70", // 39.3078 E, 48.5740 N
        "Melitopol, 509.51, 158.19", // 35.3675 E, 46.8489 N
        "Kherson, 502.63, 158.87", // 32.6169 E, 46.6354 N
        // Near the new border:
        "Zaporizhzhia (city), 508.94, 155.05", // 35.1396 E, 47.8388 N
        "Kramatorsk, 515.07, 152.17", // 37.5843 E, 48.7389 N
        // On the former Ukraine-Russia border, which the source map drew as a gap between the
        // two outlines: now inside one merged outline, without a seam.
        "former Ukraine-Russia border, 520.23, 154.85" // 39.6453 E, 47.9011 N
    })
    void movedTerritory_isInsideRussiasOutline_notUkraines(String place, double x, double y) {
        assertThat(russia.contains(x, y)).as("%s inside RU", place).isTrue();
        assertThat(ukraine.contains(x, y)).as("%s inside UA", place).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "Kyiv, 497.38, 146.60", // 30.5234 E, 50.4501 N
        "Kharkiv, 511.68, 148.10", // 36.2304 E, 49.9935 N
        "Dnipro, 508.71, 153.05", // 35.0462 E, 48.4647 N
        // The city centre (30.7233 E, 46.4825 N) lies seaward of the simplified coastline;
        // this point is about 25 km north-west of it.
        "Odesa, 497.15, 158.95", // 30.4280 E, 46.6091 N
        // Near the new border:
        "Mykolaiv, 501.07, 157.79", // 31.9946 E, 46.9750 N
        "Izium, 514.23, 150.64" // 37.2526 E, 49.2128 N
    })
    void restOfUkraine_isInsideUkrainesOutline_notRussias(String place, double x, double y) {
        assertThat(ukraine.contains(x, y)).as("%s inside UA", place).isTrue();
        assertThat(russia.contains(x, y)).as("%s inside RU", place).isFalse();
    }

    @Test
    void russiasAndUkrainesOutlines_doNotOverlap() {
        Area overlap = new Area(russia);
        overlap.intersect(new Area(ukraine));

        assertThat(overlap.isEmpty()).isTrue();
    }

    /** Guards the helper above: every shipped outline uses only path syntax it understands. */
    @Test
    void everyShippedOutline_parses() {
        WorldMap map = new WorldMap(new ObjectMapper(), new ClassPathResource("analytics/world-map.json"));

        assertThat(map.regions()).allSatisfy(region -> assertThatCode(() -> SvgPaths.parse(region.path()))
                .as(region.code())
                .doesNotThrowAnyException());
    }
}
