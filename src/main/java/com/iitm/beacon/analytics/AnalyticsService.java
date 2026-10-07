package com.iitm.beacon.analytics;

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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The homepage dashboard's figures (UC-VIEW-DASHBOARD, decisions 14, 30),
 * computed on every call by aggregate queries over {@code APPROVED}
 * testimonials only — a pending or rejected testimonial never moves a
 * number. Hidden topics and inactive achievements are left out the way the
 * gallery leaves them out (decision 28), but a testimonial whose sections
 * are all hidden still counts in the totals, scores and countries: the
 * gallery still shows it.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    /** The lowest score that counts as recommending the exchange: where the scale turns green (decision 15). */
    static final int RECOMMENDING_MIN_SCORE = 6;

    private static final TestimonialStatus COUNTED_STATUS = TestimonialStatus.APPROVED;

    private static final Comparator<CountryCountDto> COUNTRY_ORDER =
            Comparator.comparingLong(CountryCountDto::count).reversed()
                    .thenComparing(entry -> entry.country().name())
                    .thenComparing(entry -> entry.country().code());

    private static final Comparator<AchievementCountDto> ACHIEVEMENT_ORDER =
            Comparator.comparingLong(AchievementCountDto::count).reversed()
                    .thenComparingInt(entry -> entry.achievement().displayOrder())
                    .thenComparing(entry -> entry.achievement().id());

    /**
     * Groups and standalone topics share one top-level display-order range
     * (decision 11); on a full tie a group goes before a standalone topic,
     * and a lower id first.
     */
    private static final Comparator<RankedTopic> TOPIC_ORDER =
            Comparator.comparingLong((RankedTopic ranked) -> ranked.dto().count()).reversed()
                    .thenComparingInt(RankedTopic::topLevelDisplayOrder)
                    .thenComparing(ranked -> ranked.dto().kind())
                    .thenComparing(ranked -> ranked.dto().id());

    private final TestimonialRepository testimonialRepository;
    private final TestimonialSectionRepository testimonialSectionRepository;
    private final TestimonialAchievementRepository testimonialAchievementRepository;

    public AnalyticsService(
            TestimonialRepository testimonialRepository,
            TestimonialSectionRepository testimonialSectionRepository,
            TestimonialAchievementRepository testimonialAchievementRepository) {
        this.testimonialRepository = testimonialRepository;
        this.testimonialSectionRepository = testimonialSectionRepository;
        this.testimonialAchievementRepository = testimonialAchievementRepository;
    }

    /**
     * Every dashboard figure. Lists are sorted highest count first and hold
     * no zero count; with no approved testimonial they are empty and the
     * average and the recommending share are {@code null}.
     */
    public AnalyticsSummaryDto summary() {
        RecommendationScoreSummary scores =
                testimonialRepository.summarizeScoresByStatus(COUNTED_STATUS, RECOMMENDING_MIN_SCORE);
        long total = scores.testimonialCount();
        return new AnalyticsSummaryDto(
                countries(),
                achievements(),
                topics(),
                total,
                total == 0 ? null : scores.averageScore(),
                total == 0 ? null : percent(scores.countAtOrAboveThreshold(), total));
    }

    /** {@code part} as a whole percent of {@code total}, rounded half up. */
    private static int percent(long part, long total) {
        return (int) Math.round(100.0 * part / total);
    }

    private List<CountryCountDto> countries() {
        return testimonialRepository.countPerCountryByStatus(COUNTED_STATUS).stream()
                .filter(row -> row.testimonialCount() > 0)
                .map(AnalyticsService::toCountryCount)
                .sorted(COUNTRY_ORDER)
                .toList();
    }

    private static CountryCountDto toCountryCount(CountryTestimonialCount row) {
        return new CountryCountDto(new CountryDto(row.countryCode(), row.countryName()), row.testimonialCount());
    }

    private List<AchievementCountDto> achievements() {
        return testimonialAchievementRepository.countTicksPerActiveAchievementByStatus(COUNTED_STATUS).stream()
                .filter(row -> row.tickCount() > 0)
                .map(AnalyticsService::toAchievementCount)
                .sorted(ACHIEVEMENT_ORDER)
                .toList();
    }

    private static AchievementCountDto toAchievementCount(AchievementTickCount row) {
        AchievementDto achievement =
                new AchievementDto(row.achievementId(), row.slug(), row.label(), row.displayOrder(), row.active());
        return new AchievementCountDto(achievement, row.tickCount());
    }

    private List<TopicCountDto> topics() {
        Stream<RankedTopic> groups =
                testimonialSectionRepository.countTestimonialsPerActiveTopicGroup(COUNTED_STATUS).stream()
                        .map(AnalyticsService::rankGroup);
        Stream<RankedTopic> standaloneTopics =
                testimonialSectionRepository.countTestimonialsPerVisibleStandaloneTopic(COUNTED_STATUS).stream()
                        .map(AnalyticsService::rankStandaloneTopic);
        return Stream.concat(groups, standaloneTopics)
                .filter(ranked -> ranked.dto().count() > 0)
                .sorted(TOPIC_ORDER)
                .map(RankedTopic::dto)
                .toList();
    }

    private static RankedTopic rankGroup(TopicGroupTestimonialCount row) {
        return new RankedTopic(
                row.displayOrder(),
                new TopicCountDto(Kind.GROUP, row.groupId(), row.label(), row.testimonialCount()));
    }

    private static RankedTopic rankStandaloneTopic(StandaloneTopicTestimonialCount row) {
        return new RankedTopic(
                row.displayOrder(),
                new TopicCountDto(Kind.STANDALONE, row.topicId(), row.label(), row.testimonialCount()));
    }

    /** A topic entry with its top-level display order, which the answer itself doesn't carry. */
    private record RankedTopic(int topLevelDisplayOrder, TopicCountDto dto) {
    }
}
