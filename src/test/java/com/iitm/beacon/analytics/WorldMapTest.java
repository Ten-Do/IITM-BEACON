package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * {@link WorldMap}: the country outlines of the homepage map (decision 30),
 * read once from {@code analytics/world-map.json}. The shipped file must load
 * whole; a missing or malformed one must stop the application from starting
 * rather than render a broken map.
 */
class WorldMapTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static WorldMap shipped() {
        return new WorldMap(OBJECT_MAPPER, new ClassPathResource("analytics/world-map.json"));
    }

    private static WorldMap fromJson(String json) {
        return new WorldMap(OBJECT_MAPPER, new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** A map file with the given regions object (raw JSON) and a valid viewBox. */
    private static String withRegions(String regionsJson) {
        return "{\"source\": \"test\", \"viewBox\": \"0 0 900 440.71\", \"regions\": " + regionsJson + "}";
    }

    /** A map file with the given viewBox value (raw JSON) and one valid region. */
    private static String withViewBox(String viewBoxJson) {
        return "{\"viewBox\": " + viewBoxJson + ", \"regions\": {\"DE\": \"M1,1l2,2Z\"}}";
    }

    // -- the shipped file --

    @Test
    void shippedMap_has173Regions() {
        assertThat(shipped().regions()).hasSize(173);
    }

    @Test
    void shippedMap_everyCodeIsTwoUppercaseLetters_andEveryPathIsNonBlank() {
        assertThat(shipped().regions()).allSatisfy(region -> {
            assertThat(region.code()).matches("[A-Z]{2}");
            assertThat(region.path()).isNotBlank();
        });
    }

    @Test
    void shippedMap_codesAreUniqueAndSortedByCode() {
        List<String> codes =
                shipped().regions().stream().map(WorldMap.Region::code).toList();

        assertThat(codes).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void shippedMap_hasTheViewBoxOfTheConvertedSource() {
        assertThat(shipped().viewBox()).isEqualTo("0 0 900 440.71");
    }

    /** Countries too small for the source map (decision 30) are simply absent. */
    @Test
    void shippedMap_hasLargeCountries_butNotTheTooSmallOnes() {
        List<String> codes =
                shipped().regions().stream().map(WorldMap.Region::code).toList();

        assertThat(codes).contains("DE", "IN", "US", "BR", "AU");
        assertThat(codes).doesNotContain("SG", "MT", "HK", "BH");
    }

    // -- order and immutability --

    @Test
    void regions_comeOrderedByCode_whateverTheFileOrder() {
        WorldMap map = fromJson(withRegions("{\"FR\": \"M3Z\", \"AE\": \"M1Z\", \"DE\": \"M2Z\"}"));

        assertThat(map.regions())
                .containsExactly(
                        new WorldMap.Region("AE", "M1Z"), new WorldMap.Region("DE", "M2Z"), new WorldMap.Region(
                                "FR", "M3Z"));
    }

    @Test
    void regions_cannotBeModifiedByACaller() {
        WorldMap map = shipped();

        assertThatThrownBy(() -> map.regions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(map.regions()).hasSize(173);
    }

    @Test
    void unknownTopLevelFields_areIgnored() {
        WorldMap map = fromJson("{\"viewBox\": \"0 0 10 10\", \"regions\": {\"DE\": \"M1Z\"}, \"extra\": [1, 2]}");

        assertThat(map.viewBox()).isEqualTo("0 0 10 10");
        assertThat(map.regions()).containsExactly(new WorldMap.Region("DE", "M1Z"));
    }

    @Test
    void aViewBoxWithNegativeAndDecimalNumbers_isAccepted() {
        assertThat(fromJson(withViewBox("\"-10.5 0 900.25 440\"")).viewBox()).isEqualTo("-10.5 0 900.25 440");
    }

    // -- fail fast: missing resource --

    @Test
    void missingResource_failsWithAMessageNamingIt() {
        Resource missing = new ClassPathResource("analytics/no-such-map.json");

        assertThatThrownBy(() -> new WorldMap(OBJECT_MAPPER, missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-map.json");
    }

    // -- fail fast: malformed file --

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json", "{\"viewBox\": \"0 0 1 1\", ", "[]", "\"a string\"", "null", "42"})
    void contentThatIsNotAJsonObject_fails(String content) {
        assertThatThrownBy(() -> fromJson(content)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingViewBox_fails() {
        assertThatThrownBy(() -> fromJson("{\"regions\": {\"DE\": \"M1Z\"}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("viewBox");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \"", "42", "[0, 0, 900, 440]", "\"0 0 900\"",
        "\"0 0 900 440 1\"", "\"a b c d\""})
    void viewBoxThatIsNotFourNumbers_fails(String viewBoxJson) {
        assertThatThrownBy(() -> fromJson(withViewBox(viewBoxJson)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("viewBox");
    }

    @Test
    void missingRegions_fails() {
        assertThatThrownBy(() -> fromJson("{\"viewBox\": \"0 0 900 440.71\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "[\"DE\"]", "\"DE\""})
    void regionsThatAreNotANonEmptyObject_fail(String regionsJson) {
        assertThatThrownBy(() -> fromJson(withRegions(regionsJson)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "De", "D", "DEU", "D1", "12", "", " DE", "DE "})
    void aRegionCodeThatIsNotTwoUppercaseLetters_fails(String code) {
        assertThatThrownBy(() -> fromJson(withRegions("{\"FR\": \"M1Z\", \"" + code + "\": \"M2Z\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null", "42", "[\"M1Z\"]", "{\"d\": \"M1Z\"}"})
    void aRegionWhosePathIsNotANonBlankString_fails(String pathJson) {
        assertThatThrownBy(() -> fromJson(withRegions("{\"FR\": \"M1Z\", \"DE\": " + pathJson + "}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DE");
    }

    /** JSON itself would silently keep the last of two equal keys; a map with one twice is broken. */
    @Test
    void aRegionCodeListedTwice_fails() {
        assertThatThrownBy(() -> fromJson(withRegions("{\"DE\": \"M1Z\", \"DE\": \"M2Z\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DE");
    }
}
