package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.iitm.beacon.analytics.TopicCountDto.Kind;
import com.iitm.beacon.domain.achievement.AchievementTickCount;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.testimonial.CountryTestimonialCount;
import com.iitm.beacon.domain.testimonial.RecommendationScoreSummary;
import com.iitm.beacon.domain.testimonial.StandaloneTopicTestimonialCount;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.testimonial.TopicGroupTestimonialCount;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link AnalyticsService} over repository rows it can't rely on: the
 * aggregate queries promise no order, so every tie-break is checked against
 * rows arriving in the worst order — which the database tests can't force —
 * and a zero count, which a real {@code GROUP BY} never yields, must still
 * never reach the answer (decision 30).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsServiceRowHandlingTest {

    private static final TestimonialStatus APPROVED = TestimonialStatus.APPROVED;

    @Mock
    private TestimonialRepository testimonialRepository;

    @Mock
    private TestimonialSectionRepository testimonialSectionRepository;

    @Mock
    private TestimonialAchievementRepository testimonialAchievementRepository;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(
                testimonialRepository, testimonialSectionRepository, testimonialAchievementRepository);
        when(testimonialRepository.summarizeScoresByStatus(eq(APPROVED), anyInt()))
                .thenReturn(new RecommendationScoreSummary(10, 7.0, 8));
        when(testimonialRepository.countPerCountryByStatus(APPROVED)).thenReturn(List.of());
        when(testimonialAchievementRepository.countTicksPerActiveAchievementByStatus(APPROVED))
                .thenReturn(List.of());
        when(testimonialSectionRepository.countTestimonialsPerActiveTopicGroup(APPROVED)).thenReturn(List.of());
        when(testimonialSectionRepository.countTestimonialsPerVisibleStandaloneTopic(APPROVED))
                .thenReturn(List.of());
    }

    @Test
    void countries_inTheWorstOrder_areSortedByCountThenNameThenCode() {
        when(testimonialRepository.countPerCountryByStatus(APPROVED)).thenReturn(List.of(
                new CountryTestimonialCount("XB", "Testland", 1),
                new CountryTestimonialCount("XA", "Testland", 1),
                new CountryTestimonialCount("CH", "Switzerland", 1),
                new CountryTestimonialCount("DE", "Germany", 1),
                new CountryTestimonialCount("IN", "India", 2)));

        assertThat(analyticsService.summary().testimonialsByCountry())
                .extracting(entry -> entry.country().code())
                .containsExactly("IN", "DE", "CH", "XA", "XB");
    }

    @Test
    void achievements_inTheWorstOrder_areSortedByCountThenDisplayOrderThenId() {
        when(testimonialAchievementRepository.countTicksPerActiveAchievementByStatus(APPROVED)).thenReturn(List.of(
                new AchievementTickCount(9L, "nine", "A label", 1, true, 1),
                new AchievementTickCount(3L, "three", "Z label", 1, true, 1),
                new AchievementTickCount(5L, "five", "M label", 0, true, 1),
                new AchievementTickCount(7L, "seven", "B label", 2, true, 2)));

        assertThat(analyticsService.summary().achievementCounts())
                .extracting(entry -> entry.achievement().id())
                .containsExactly(7L, 5L, 3L, 9L);
    }

    @Test
    void topics_inTheWorstOrder_areSortedByCountThenTopLevelDisplayOrderThenGroupFirstThenId() {
        when(testimonialSectionRepository.countTestimonialsPerActiveTopicGroup(APPROVED)).thenReturn(List.of(
                new TopicGroupTestimonialCount(12L, "Group twelve", 5, 1),
                new TopicGroupTestimonialCount(11L, "Group eleven", 5, 1),
                new TopicGroupTestimonialCount(1L, "Group one", 9, 1)));
        when(testimonialSectionRepository.countTestimonialsPerVisibleStandaloneTopic(APPROVED)).thenReturn(List.of(
                new StandaloneTopicTestimonialCount(40L, "Topic forty", 5, 1),
                new StandaloneTopicTestimonialCount(30L, "Topic thirty", 5, 1),
                new StandaloneTopicTestimonialCount(2L, "Topic two", 1, 1),
                new StandaloneTopicTestimonialCount(99L, "Topic ninety-nine", 50, 3)));

        assertThat(analyticsService.summary().testimonialCountsByTopic())
                .extracting(TopicCountDto::kind, TopicCountDto::id)
                .containsExactly(
                        tuple(Kind.STANDALONE, 99L),
                        tuple(Kind.STANDALONE, 2L),
                        tuple(Kind.GROUP, 11L),
                        tuple(Kind.GROUP, 12L),
                        tuple(Kind.STANDALONE, 30L),
                        tuple(Kind.STANDALONE, 40L),
                        tuple(Kind.GROUP, 1L));
    }

    @Test
    void zeroCountRows_neverReachTheAnswer() {
        when(testimonialRepository.countPerCountryByStatus(APPROVED)).thenReturn(List.of(
                new CountryTestimonialCount("DE", "Germany", 0), new CountryTestimonialCount("IN", "India", 1)));
        when(testimonialAchievementRepository.countTicksPerActiveAchievementByStatus(APPROVED)).thenReturn(List.of(
                new AchievementTickCount(1L, "zero", "Zero", 1, true, 0),
                new AchievementTickCount(2L, "one", "One", 2, true, 1)));
        when(testimonialSectionRepository.countTestimonialsPerActiveTopicGroup(APPROVED))
                .thenReturn(List.of(new TopicGroupTestimonialCount(1L, "Empty group", 1, 0)));
        when(testimonialSectionRepository.countTestimonialsPerVisibleStandaloneTopic(APPROVED)).thenReturn(List.of(
                new StandaloneTopicTestimonialCount(5L, "Empty topic", 2, 0),
                new StandaloneTopicTestimonialCount(6L, "Topic", 3, 1)));

        AnalyticsSummaryDto summary = analyticsService.summary();

        assertThat(summary.testimonialsByCountry()).extracting(entry -> entry.country().code()).containsExactly("IN");
        assertThat(summary.achievementCounts()).extracting(entry -> entry.achievement().slug()).containsExactly("one");
        assertThat(summary.testimonialCountsByTopic()).extracting(TopicCountDto::id).containsExactly(6L);
    }
}
