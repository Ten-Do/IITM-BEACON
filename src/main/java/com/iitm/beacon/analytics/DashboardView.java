package com.iitm.beacon.analytics;

import com.iitm.beacon.analytics.TopicCountDto.Kind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything {@code analytics/dashboard.html} renders (decision 30,
 * architecture §18): {@link AnalyticsSummaryDto}'s figures plus the
 * presentation the page adds on top — each map region's shade, link and
 * tooltip, the average rounded for display and for its colour, and the
 * top-N splits — so the template only iterates and branches.
 *
 * @param empty no approved testimonial yet: the page shows its empty state instead
 * @param averageScore the average with one decimal, rounded half up ({@code "8.1"}); null when empty
 * @param averageScoreColour the shown average ({@code averageScore}) rounded half up to a whole score,
 *     0–10, for {@code var(--score-N)} — so 7.46, shown as "7.5", takes score 8's colour; null when empty
 * @param recommendingPercent the share scoring 6 or more, as a whole percent; null when empty
 * @param countriesRepresented how many countries the approved testimonials come from
 * @param mapRegions every region of the {@link WorldMap}, in its order
 */
record DashboardView(
        boolean empty,
        long totalApprovedTestimonials,
        String averageScore,
        Integer averageScoreColour,
        Integer recommendingPercent,
        int countriesRepresented,
        String mapViewBox,
        List<MapRegion> mapRegions,
        Split<CountryChip> countryChips,
        Split<TopicRow> topicRows,
        Split<AchievementRow> achievementRows) {

    /** Country chips shown before "+ N more countries". */
    static final int COUNTRY_CHIPS_SHOWN = 6;

    /** Rows of each list shown before "Show all". */
    static final int LIST_ROWS_SHOWN = 5;

    /** The darkest map shade ({@code map-shade-5}); 0 is a country with no approved testimonial. */
    static final int DARKEST_SHADE = 5;

    private static final String GALLERY = "/gallery";

    DashboardView {
        // Defensive copy (SpotBugs EI_EXPOSE_REP/EI_EXPOSE_REP2).
        mapRegions = List.copyOf(mapRegions);
    }

    static DashboardView of(AnalyticsSummaryDto summary, WorldMap worldMap) {
        List<CountryCountDto> countries = summary.testimonialsByCountry();
        BigDecimal average = summary.averageRecommendationScore() == null
                ? null
                : BigDecimal.valueOf(summary.averageRecommendationScore()).setScale(1, RoundingMode.HALF_UP);
        return new DashboardView(
                summary.totalApprovedTestimonials() == 0,
                summary.totalApprovedTestimonials(),
                average == null ? null : average.toPlainString(),
                average == null ? null : average.setScale(0, RoundingMode.HALF_UP).intValueExact(),
                summary.recommendingPercent(),
                countries.size(),
                worldMap.viewBox(),
                mapRegions(countries, worldMap),
                Split.of(countries, COUNTRY_CHIPS_SHOWN, DashboardView::countryChip),
                Split.of(summary.testimonialCountsByTopic(), LIST_ROWS_SHOWN, DashboardView::topicRow),
                Split.of(summary.achievementCounts(), LIST_ROWS_SHOWN, DashboardView::achievementRow));
    }

    private static List<MapRegion> mapRegions(List<CountryCountDto> countries, WorldMap worldMap) {
        Map<String, CountryCountDto> byCode = new HashMap<>();
        long max = 0;
        for (CountryCountDto entry : countries) {
            byCode.putIfAbsent(entry.country().code(), entry);
            max = Math.max(max, entry.count());
        }
        long highest = max;
        return worldMap.regions().stream()
                .map(region -> mapRegion(region, byCode.get(region.code()), highest))
                .toList();
    }

    private static MapRegion mapRegion(WorldMap.Region region, CountryCountDto entry, long max) {
        if (entry == null) {
            return new MapRegion(region.code(), region.path(), 0, null, null);
        }
        int shade = (int) Math.ceilDiv(DARKEST_SHADE * entry.count(), max);
        String title = entry.country().name() + " — " + testimonials(entry.count());
        return new MapRegion(region.code(), region.path(), shade, countryHref(region.code()), title);
    }

    private static String testimonials(long count) {
        return count + (count == 1 ? " testimonial" : " testimonials");
    }

    private static String countryHref(String code) {
        return GALLERY + "?country=" + code;
    }

    private static CountryChip countryChip(CountryCountDto entry) {
        return new CountryChip(entry.country().name(), entry.count(), countryHref(entry.country().code()));
    }

    /** A group filters by {@code groupIds}, a standalone topic by {@code topicIds} (decision 29). */
    private static TopicRow topicRow(TopicCountDto entry) {
        String parameter = entry.kind() == Kind.GROUP ? "groupIds" : "topicIds";
        return new TopicRow(entry.label(), entry.count(), GALLERY + "?" + parameter + "=" + entry.id());
    }

    private static AchievementRow achievementRow(AchievementCountDto entry) {
        return new AchievementRow(entry.achievement().label(), entry.count());
    }

    /**
     * One country outline on the map. {@code shade} is {@code ceil(5 · count
     * / max)} (1–5) for a country with approved testimonials, which then also
     * has a gallery link and a tooltip; 0, with neither, for the rest.
     */
    record MapRegion(String code, String path, int shade, String href, String title) {
    }

    /** A country chip under the map, linking to the gallery filtered by it. */
    record CountryChip(String name, long count, String href) {
    }

    /** A "Testimonials by topic" row, linking to the gallery filtered by the group or topic. */
    record TopicRow(String label, long count, String href) {
    }

    /** A "Most common achievements" row; the gallery has no achievement filter, so no link. */
    record AchievementRow(String label, long count) {
    }

    /** The {@code first} entries shown up front, the {@code rest} behind a {@code <details>}. */
    record Split<T>(List<T> first, List<T> rest) {

        Split {
            // Defensive copies (SpotBugs EI_EXPOSE_REP/EI_EXPOSE_REP2).
            first = List.copyOf(first);
            rest = List.copyOf(rest);
        }

        static <S, T> Split<T> of(List<S> all, int shown, Function<S, T> mapper) {
            List<T> mapped = all.stream().map(mapper).toList();
            int cut = Math.min(shown, mapped.size());
            return new Split<>(mapped.subList(0, cut), mapped.subList(cut, mapped.size()));
        }
    }
}
