package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.analytics.DashboardView.AchievementRow;
import com.iitm.beacon.analytics.DashboardView.CountryChip;
import com.iitm.beacon.analytics.DashboardView.MapRegion;
import com.iitm.beacon.analytics.DashboardView.TopicRow;
import com.iitm.beacon.analytics.TopicCountDto.Kind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;

/**
 * {@link DashboardView}: the presentation the homepage dashboard adds on top
 * of {@link AnalyticsSummaryDto} (decision 30, architecture §18) — map
 * shades, links and tooltips, the rounded average and its colour, and the
 * top-N splits — with no Spring and no database. The map is a small fake
 * with eight regions; Singapore is deliberately not on it, like on the real
 * one.
 */
class DashboardViewTest {

    private static final WorldMap MAP = new WorldMap(
            new ObjectMapper(),
            new ByteArrayResource(("{\"viewBox\": \"0 0 900 440.71\", \"regions\": {"
                            + "\"US\": \"M-us\", \"DE\": \"M-de\", \"FR\": \"M-fr\", \"IN\": \"M-in\","
                            + " \"JP\": \"M-jp\", \"BR\": \"M-br\", \"PT\": \"M-pt\", \"SE\": \"M-se\"}}")
                    .getBytes(StandardCharsets.UTF_8)));

    private static final List<String> MAP_ORDER = List.of("BR", "DE", "FR", "IN", "JP", "PT", "SE", "US");

    // -- builders --

    private static CountryCountDto country(String code, String name, long count) {
        return new CountryCountDto(new CountryDto(code, name), count);
    }

    private static TopicCountDto group(long id, String label, long count) {
        return new TopicCountDto(Kind.GROUP, id, label, count);
    }

    private static TopicCountDto standalone(long id, String label, long count) {
        return new TopicCountDto(Kind.STANDALONE, id, label, count);
    }

    private static AchievementCountDto achievement(long id, String label, long count) {
        return new AchievementCountDto(new AchievementDto(id, "slug-" + id, label, (int) id, true), count);
    }

    private static AnalyticsSummaryDto withCountries(List<CountryCountDto> countries) {
        long total = countries.stream().mapToLong(CountryCountDto::count).sum();
        return new AnalyticsSummaryDto(countries, List.of(), List.of(), total, 8.0, 100);
    }

    private static AnalyticsSummaryDto withAverage(double average) {
        return new AnalyticsSummaryDto(List.of(country("DE", "Germany", 1)), List.of(), List.of(), 1, average, 100);
    }

    private static AnalyticsSummaryDto withTopics(List<TopicCountDto> topics) {
        return new AnalyticsSummaryDto(List.of(country("DE", "Germany", 1)), List.of(), topics, 1, 8.0, 100);
    }

    private static AnalyticsSummaryDto withAchievements(List<AchievementCountDto> achievements) {
        return new AnalyticsSummaryDto(List.of(country("DE", "Germany", 1)), achievements, List.of(), 1, 8.0, 100);
    }

    private static AnalyticsSummaryDto emptySummary() {
        return new AnalyticsSummaryDto(List.of(), List.of(), List.of(), 0, null, null);
    }

    /** {@code n} countries with descending counts (n, n-1, … 1), all on the fake map while there are regions. */
    private static List<CountryCountDto> countries(int n) {
        List<String> codes = List.of("DE", "FR", "US", "BR", "JP", "PT", "SE", "IN");
        return IntStream.range(0, n)
                .mapToObj(i -> country(codes.get(i), "Country " + i, n - i))
                .toList();
    }

    private static List<TopicCountDto> topics(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> group(i + 1, "Topic " + i, n - i))
                .toList();
    }

    private static List<AchievementCountDto> achievements(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> achievement(i + 1, "Feat " + i, n - i))
                .toList();
    }

    private static MapRegion region(DashboardView view, String code) {
        return view.mapRegions().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no region " + code));
    }

    // -- empty --

    @Test
    void emptySummary_isEmpty_withNoFiguresNoChipsAndNoRows() {
        DashboardView view = DashboardView.of(emptySummary(), MAP);

        assertThat(view.empty()).isTrue();
        assertThat(view.totalApprovedTestimonials()).isZero();
        assertThat(view.averageScore()).isNull();
        assertThat(view.averageScoreColour()).isNull();
        assertThat(view.recommendingPercent()).isNull();
        assertThat(view.countriesRepresented()).isZero();
        assertThat(view.countryChips().first()).isEmpty();
        assertThat(view.countryChips().rest()).isEmpty();
        assertThat(view.topicRows().first()).isEmpty();
        assertThat(view.topicRows().rest()).isEmpty();
        assertThat(view.achievementRows().first()).isEmpty();
        assertThat(view.achievementRows().rest()).isEmpty();
    }

    /** No max to divide by: every region is unshaded and unlinked (no division by zero). */
    @Test
    void emptySummary_stillListsEveryRegion_allUnshadedAndUnlinked() {
        DashboardView view = DashboardView.of(emptySummary(), MAP);

        assertThat(view.mapRegions()).extracting(MapRegion::code).containsExactlyElementsOf(MAP_ORDER);
        assertThat(view.mapRegions()).allSatisfy(region -> {
            assertThat(region.shade()).isZero();
            assertThat(region.href()).isNull();
            assertThat(region.title()).isNull();
        });
    }

    @Test
    void oneApprovedTestimonial_isNotEmpty() {
        DashboardView view = DashboardView.of(withCountries(List.of(country("DE", "Germany", 1))), MAP);

        assertThat(view.empty()).isFalse();
        assertThat(view.totalApprovedTestimonials()).isEqualTo(1);
    }

    // -- stat figures --

    @Test
    void totalsAndRecommendingPercent_passThrough() {
        AnalyticsSummaryDto summary = new AnalyticsSummaryDto(
                List.of(country("DE", "Germany", 120), country("FR", "France", 8)), List.of(), List.of(), 128, 8.1,
                64);

        DashboardView view = DashboardView.of(summary, MAP);

        assertThat(view.totalApprovedTestimonials()).isEqualTo(128);
        assertThat(view.recommendingPercent()).isEqualTo(64);
        assertThat(view.countriesRepresented()).isEqualTo(2);
    }

    /** 0 % is a real figure (nobody recommends it), not "absent". */
    @Test
    void zeroRecommendingPercent_staysZero() {
        AnalyticsSummaryDto summary =
                new AnalyticsSummaryDto(List.of(country("DE", "Germany", 3)), List.of(), List.of(), 3, 2.0, 0);

        assertThat(DashboardView.of(summary, MAP).recommendingPercent()).isZero();
    }

    /** A country too small for the map still counts as represented. */
    @Test
    void countriesRepresented_includesCountriesNotOnTheMap() {
        DashboardView view = DashboardView.of(
                withCountries(List.of(country("DE", "Germany", 2), country("SG", "Singapore", 1))), MAP);

        assertThat(view.countriesRepresented()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
        "8.1, 8.1",
        "7.0, 7.0",
        "10.0, 10.0",
        "0.0, 0.0",
        "7.25, 7.3",
        "7.95, 8.0",
        "9.96, 10.0",
        "9.95, 10.0",
        "9.94, 9.9",
        "7.04, 7.0",
        "7.05, 7.1",
        "0.04, 0.0",
        "0.05, 0.1",
        "8.333333333333334, 8.3",
        "8.666666666666666, 8.7",
        "7.5, 7.5",
    })
    void averageScore_isShownWithOneDecimal_roundedHalfUp(double average, String shown) {
        assertThat(DashboardView.of(withAverage(average), MAP).averageScore()).isEqualTo(shown);
    }

    /** Locale.ROOT: a decimal point, whatever the server's default locale (a German one would print a comma). */
    @Test
    void averageScore_usesADecimalPoint_evenUnderACommaLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            assertThat(DashboardView.of(withAverage(8.14), MAP).averageScore()).isEqualTo("8.1");
        } finally {
            Locale.setDefault(before);
        }
    }

    /** The colour follows the figure the visitor sees: 7.46 shows as "7.5", so it takes score 8's colour. */
    @ParameterizedTest
    @CsvSource({
        "7.5, 8",
        "7.49, 8",
        "7.46, 8",
        "7.44, 7",
        "0.0, 0",
        "0.44, 0",
        "0.45, 1",
        "0.5, 1",
        "5.5, 6",
        "9.5, 10",
        "9.96, 10",
        "10.0, 10",
        "8.1, 8",
    })
    void averageScoreColour_isTheShownAverageRoundedHalfUpToAWholeScore(double average, int colour) {
        assertThat(DashboardView.of(withAverage(average), MAP).averageScoreColour()).isEqualTo(colour);
    }

    // -- map regions --

    @Test
    void mapRegions_coverEveryRegionOfTheMap_inTheMapsOrder_withTheMapsPaths() {
        DashboardView view = DashboardView.of(withCountries(List.of(country("DE", "Germany", 3))), MAP);

        assertThat(view.mapViewBox()).isEqualTo("0 0 900 440.71");
        assertThat(view.mapRegions()).extracting(MapRegion::code).containsExactlyElementsOf(MAP_ORDER);
        assertThat(region(view, "DE").path()).isEqualTo("M-de");
        assertThat(region(view, "US").path()).isEqualTo("M-us");
    }

    @Test
    void theOnlyCountry_isTheDarkestShade() {
        DashboardView view = DashboardView.of(withCountries(List.of(country("FR", "France", 1))), MAP);

        assertThat(region(view, "FR").shade()).isEqualTo(5);
    }

    /** Shade = ceil(5 · count / max): the steps fall at exactly 20 %, 40 %, 60 % and 80 % of the max. */
    @ParameterizedTest
    @CsvSource({
        "1, 50, 1",
        "1, 10, 1",
        "2, 10, 1",
        "3, 10, 2",
        "4, 10, 2",
        "5, 10, 3",
        "6, 10, 3",
        "7, 10, 4",
        "8, 10, 4",
        "9, 10, 5",
        "10, 10, 5",
        "49, 50, 5",
        "40, 50, 4",
        "41, 50, 5",
        "1, 2, 3",
        "1, 3, 2",
    })
    void shade_isCeilOfFiveTimesTheCountOverTheMax(long count, long max, int shade) {
        DashboardView view = DashboardView.of(
                withCountries(List.of(country("DE", "Germany", max), country("FR", "France", count))), MAP);

        assertThat(region(view, "FR").shade()).isEqualTo(shade);
        assertThat(region(view, "DE").shade()).isEqualTo(5);
    }

    /** The max is the highest country count, even if that country isn't drawn on the map. */
    @Test
    void theMax_canBeACountryThatIsNotOnTheMap() {
        DashboardView view = DashboardView.of(
                withCountries(List.of(country("SG", "Singapore", 10), country("DE", "Germany", 5))), MAP);

        assertThat(region(view, "DE").shade()).isEqualTo(3);
        assertThat(view.mapRegions()).extracting(MapRegion::code).doesNotContain("SG");
        assertThat(view.countryChips().first()).extracting(CountryChip::name).containsExactly("Singapore", "Germany");
    }

    @Test
    void aRepresentedRegion_linksToTheGalleryCountryFilter_withACountTooltip() {
        DashboardView view = DashboardView.of(withCountries(List.of(country("DE", "Germany", 12))), MAP);

        MapRegion germany = region(view, "DE");
        assertThat(germany.href()).isEqualTo("/gallery?country=DE");
        assertThat(germany.title()).isEqualTo("Germany — 12 testimonials");
    }

    @Test
    void theTooltip_isSingularForOneTestimonial() {
        DashboardView view = DashboardView.of(
                withCountries(List.of(country("DE", "Germany", 2), country("FR", "France", 1))), MAP);

        assertThat(region(view, "FR").title()).isEqualTo("France — 1 testimonial");
        assertThat(region(view, "DE").title()).isEqualTo("Germany — 2 testimonials");
    }

    /** The name comes from the summary, not from the map file. */
    @Test
    void theTooltip_usesTheSummarysCountryName() {
        DashboardView view =
                DashboardView.of(withCountries(List.of(country("US", "United States of America", 4))), MAP);

        assertThat(region(view, "US").title()).isEqualTo("United States of America — 4 testimonials");
    }

    @Test
    void aRegionWithNoApprovedTestimonial_isUnshadedUnlinkedAndHasNoTooltip() {
        DashboardView view = DashboardView.of(withCountries(List.of(country("DE", "Germany", 7))), MAP);

        MapRegion france = region(view, "FR");
        assertThat(france.shade()).isZero();
        assertThat(france.href()).isNull();
        assertThat(france.title()).isNull();
        assertThat(france.path()).isEqualTo("M-fr");
    }

    /** Can't happen with the service's GROUP BY; if it ever did, the first (highest) entry wins, without a crash. */
    @Test
    void aCountryListedTwice_isDrawnFromItsFirstEntry() {
        DashboardView view = DashboardView.of(
                withCountries(List.of(country("DE", "Germany", 10), country("DE", "Germany", 1))), MAP);

        assertThat(region(view, "DE").shade()).isEqualTo(5);
        assertThat(region(view, "DE").title()).isEqualTo("Germany — 10 testimonials");
    }

    // -- country chips: the top 6, the rest behind "+ N more countries" --

    @Test
    void countryChips_carryNameCountAndGalleryLink_inTheSummarysOrder() {
        DashboardView view = DashboardView.of(
                withCountries(List.of(country("DE", "Germany", 12), country("SG", "Singapore", 3))), MAP);

        assertThat(view.countryChips().first())
                .containsExactly(
                        new CountryChip("Germany", 12, "/gallery?country=DE"),
                        new CountryChip("Singapore", 3, "/gallery?country=SG"));
        assertThat(view.countryChips().rest()).isEmpty();
    }

    @Test
    void sixCountries_allShowAsChips_withNothingLeftOver() {
        DashboardView view = DashboardView.of(withCountries(countries(6)), MAP);

        assertThat(view.countryChips().first()).hasSize(6);
        assertThat(view.countryChips().rest()).isEmpty();
    }

    @Test
    void sevenCountries_showSixChips_andTheSeventhInTheRest() {
        DashboardView view = DashboardView.of(withCountries(countries(7)), MAP);

        assertThat(view.countryChips().first())
                .extracting(CountryChip::name)
                .containsExactly("Country 0", "Country 1", "Country 2", "Country 3", "Country 4", "Country 5");
        assertThat(view.countryChips().rest()).extracting(CountryChip::name).containsExactly("Country 6");
    }

    @Test
    void oneCountry_isOneChip() {
        DashboardView view = DashboardView.of(withCountries(countries(1)), MAP);

        assertThat(view.countryChips().first()).hasSize(1);
        assertThat(view.countryChips().rest()).isEmpty();
    }

    // -- topic rows: the top 5, the rest behind "Show all" --

    @Test
    void topicRows_linkAGroupByGroupIds_andAStandaloneTopicByTopicIds_evenWhenTheIdsAreEqual() {
        DashboardView view = DashboardView.of(
                withTopics(List.of(group(10, "Academics", 4), standalone(10, "General", 2))), MAP);

        assertThat(view.topicRows().first())
                .containsExactly(
                        new TopicRow("Academics", 4, "/gallery?groupIds=10"),
                        new TopicRow("General", 2, "/gallery?topicIds=10"));
    }

    @Test
    void fiveTopics_allShow_withNothingLeftOver() {
        DashboardView view = DashboardView.of(withTopics(topics(5)), MAP);

        assertThat(view.topicRows().first()).hasSize(5);
        assertThat(view.topicRows().rest()).isEmpty();
    }

    @Test
    void sixTopics_showFive_andTheSixthInTheRest() {
        DashboardView view = DashboardView.of(withTopics(topics(6)), MAP);

        assertThat(view.topicRows().first())
                .extracting(TopicRow::label)
                .containsExactly("Topic 0", "Topic 1", "Topic 2", "Topic 3", "Topic 4");
        assertThat(view.topicRows().rest()).containsExactly(new TopicRow("Topic 5", 1, "/gallery?groupIds=6"));
    }

    @Test
    void noTopics_noRows() {
        DashboardView view = DashboardView.of(withTopics(List.of()), MAP);

        assertThat(view.topicRows().first()).isEmpty();
        assertThat(view.topicRows().rest()).isEmpty();
    }

    // -- achievement rows: the top 5, the rest behind "Show all"; no link --

    @Test
    void achievementRows_carryLabelAndCount() {
        DashboardView view =
                DashboardView.of(withAchievements(List.of(achievement(3, "Made new friends here", 9))), MAP);

        assertThat(view.achievementRows().first()).containsExactly(new AchievementRow("Made new friends here", 9));
    }

    @Test
    void fiveAchievements_allShow_withNothingLeftOver() {
        DashboardView view = DashboardView.of(withAchievements(achievements(5)), MAP);

        assertThat(view.achievementRows().first()).hasSize(5);
        assertThat(view.achievementRows().rest()).isEmpty();
    }

    @Test
    void sixAchievements_showFive_andTheSixthInTheRest() {
        DashboardView view = DashboardView.of(withAchievements(achievements(6)), MAP);

        assertThat(view.achievementRows().first())
                .extracting(AchievementRow::label)
                .containsExactly("Feat 0", "Feat 1", "Feat 2", "Feat 3", "Feat 4");
        assertThat(view.achievementRows().rest()).containsExactly(new AchievementRow("Feat 5", 1));
    }

    @Test
    void manyAchievements_keepTheirOrder_acrossTheSplit() {
        DashboardView view = DashboardView.of(withAchievements(achievements(12)), MAP);

        List<String> all = new ArrayList<>(
                view.achievementRows().first().stream().map(AchievementRow::label).toList());
        all.addAll(view.achievementRows().rest().stream().map(AchievementRow::label).toList());
        assertThat(all).containsExactlyElementsOf(IntStream.range(0, 12).mapToObj(i -> "Feat " + i).toList());
    }

    // -- the view is read-only --

    @Test
    void theViewsListsCannotBeModified() {
        DashboardView view = DashboardView.of(withCountries(countries(7)), MAP);

        assertThatThrownBy(() -> view.mapRegions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.countryChips().first().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.countryChips().rest().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
