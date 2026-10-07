package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AnalyticsService#summary()} — the totals, the score figures and the
 * per-country counts (decision 30), always over {@code APPROVED}
 * testimonials only.
 */
@SpringBootTest
@Transactional
class AnalyticsServiceTotalsTest {

    private static final AnalyticsSummaryDto NOTHING_APPROVED =
            new AnalyticsSummaryDto(List.of(), List.of(), List.of(), 0, null, null);

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private AnalyticsTestData data;

    @BeforeEach
    void setUp() {
        data = new AnalyticsTestData(
                testimonialRepository,
                countryRepository,
                topicRepository,
                achievementRepository,
                emailLookupHashService);
    }

    private void approvedWithScores(int... scores) {
        for (int score : scores) {
            data.approved().score(score).sections("general").save();
        }
    }

    private static CountryCountDto country(String code, String name, long count) {
        return new CountryCountDto(new CountryDto(code, name), count);
    }

    // ---- no approved testimonial ----

    @Test
    void noTestimonialAtAll_totalIsZero_scoreFiguresAreNull_andEveryListIsEmpty() {
        assertThat(analyticsService.summary()).isEqualTo(NOTHING_APPROVED);
    }

    @Test
    void onlyPendingAndRejectedTestimonials_lookExactlyLikeNoTestimonialAtAll() {
        data.pending().from("DE").score(9).sections("academics_teaching", "general").ticks("made_new_friends").save();
        data.rejected().from("FR").score(2).sections("romance").ticks("missed_home").save();

        assertThat(analyticsService.summary()).isEqualTo(NOTHING_APPROVED);
    }

    @Test
    void pendingAndRejectedTestimonials_changeNoFigureOfTheApprovedOnes() {
        data.approved().from("IN").score(7).sections("academics_teaching", "general").ticks("made_new_friends").save();
        data.approved().from("DE").score(5).sections("romance").ticks("made_new_friends", "missed_home").save();
        AnalyticsSummaryDto before = analyticsService.summary();

        // Same and new countries, topics and achievements, scores on both sides of 6.
        data.pending().from("IN").score(10).sections("academics_difficulty", "general")
                .ticks("made_new_friends").save();
        data.pending().from("US").score(0).sections("travel_did").ticks("traveled_within_india").save();
        data.rejected().from("DE").score(1).sections("romance", "academics_teaching").ticks("missed_home").save();
        data.rejected().from("FR").score(9).sections("new_friendships").ticks("learned_to_budget").save();

        assertThat(analyticsService.summary()).isEqualTo(before);
    }

    // ---- score figures ----

    @ParameterizedTest(name = "a single testimonial scoring {0} -> average {0}, {1}% recommending")
    @CsvSource({"0, 0", "5, 0", "6, 100", "10, 100"})
    void singleApprovedTestimonial_averageIsItsScore_andSixIsTheFirstRecommendingScore(int score, int percent) {
        approvedWithScores(score);

        AnalyticsSummaryDto summary = analyticsService.summary();

        assertThat(summary.totalApprovedTestimonials()).isEqualTo(1);
        assertThat(summary.averageRecommendationScore()).isEqualTo((double) score);
        assertThat(summary.recommendingPercent()).isEqualTo(percent);
    }

    @Test
    void average_isTheExactMean_notAnIntegerDivision() {
        approvedWithScores(7, 8);

        assertThat(analyticsService.summary().averageRecommendationScore()).isEqualTo(7.5);
    }

    @Test
    void average_isNotRoundedToOneDecimal() {
        approvedWithScores(5, 6, 8);

        assertThat(analyticsService.summary().averageRecommendationScore()).isCloseTo(19.0 / 3, within(1e-9));
    }

    @ParameterizedTest(name = "scores {0} -> {1}%")
    @CsvSource({
        "'6 5 5', 33",
        "'6 6 5', 67",
        "'6 5 5 5 5 5 5 5', 13",
        "'6 6 6 6 6 6 6 5', 88",
        "'5 6', 50",
        "'0 1 2 3 4 5', 0",
        "'6 7 8 9 10', 100"
    })
    void recommendingPercent_isTheShareScoringSixOrMore_roundedHalfUpToAWholePercent(String scores, int percent) {
        approvedWithScores(Arrays.stream(scores.split(" ")).mapToInt(Integer::parseInt).toArray());

        assertThat(analyticsService.summary().recommendingPercent()).isEqualTo(percent);
    }

    // ---- countries ----

    @Test
    void countries_countEachApprovedTestimonialOnce_howeverManySectionsAndTicksItHas() {
        data.approved().from("IN").sections("academics_teaching", "academics_difficulty", "general")
                .ticks("made_new_friends", "missed_home").save();
        data.approved().from("IN").sections("general").save();

        assertThat(analyticsService.summary().testimonialsByCountry()).containsExactly(country("IN", "India", 2));
    }

    @Test
    void countries_byCountDescending_thenByName_notByCode() {
        data.approved().from("US").save();
        data.approved().from("CH").save();
        data.approved().from("IN").save();
        data.approved().from("DE").save();
        data.approved().from("IN").save();
        data.approved().from("AT").save();

        assertThat(analyticsService.summary().testimonialsByCountry()).containsExactly(
                country("IN", "India", 2),
                country("AT", "Austria", 1),
                country("DE", "Germany", 1),
                country("CH", "Switzerland", 1),
                country("US", "United States of America", 1));
    }

    @Test
    void countries_sameCountAndSameName_goByCode() {
        countryRepository.saveAndFlush(Country.builder().code("XB").name("Testland").build());
        countryRepository.saveAndFlush(Country.builder().code("XA").name("Testland").build());
        data.approved().from("XB").save();
        data.approved().from("XA").save();

        assertThat(analyticsService.summary().testimonialsByCountry())
                .containsExactly(country("XA", "Testland", 1), country("XB", "Testland", 1));
    }

    // ---- hidden sections (decision 28) ----

    @Test
    void testimonialWhoseSectionsAreAllHidden_stillCountsInTotalsScoresCountriesAndTicks() {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        data.approved().from("DE").score(4)
                .sections(catalog.inactiveTopic(), catalog.topicInInactiveGroup())
                .ticks("made_new_friends")
                .save();
        data.approved().from("IN").score(8).sections("general").save();

        AnalyticsSummaryDto summary = analyticsService.summary();

        assertThat(summary.totalApprovedTestimonials()).isEqualTo(2);
        assertThat(summary.averageRecommendationScore()).isEqualTo(6.0);
        assertThat(summary.recommendingPercent()).isEqualTo(50);
        assertThat(summary.testimonialsByCountry())
                .containsExactly(country("DE", "Germany", 1), country("IN", "India", 1));
        assertThat(summary.achievementCounts())
                .extracting(entry -> entry.achievement().slug(), AchievementCountDto::count)
                .containsExactly(tuple("made_new_friends", 1L));
        assertThat(summary.testimonialCountsByTopic())
                .extracting(TopicCountDto::label)
                .containsExactly("General");
    }
}
