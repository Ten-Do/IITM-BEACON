package com.iitm.beacon.analytics;

import java.util.List;

/**
 * The homepage dashboard figures ({@code AnalyticsSummary} in api-spec),
 * over approved testimonials only (decision 30). Each list is already
 * sorted, highest count first, and holds no zero count. With no approved
 * testimonial every list is empty and both score figures are {@code null}.
 *
 * @param averageRecommendationScore the exact mean score, unrounded
 * @param recommendingPercent the share scoring 6 or more, rounded half up to a whole percent
 */
public record AnalyticsSummaryDto(
        List<CountryCountDto> testimonialsByCountry,
        List<AchievementCountDto> achievementCounts,
        List<TopicCountDto> testimonialCountsByTopic,
        long totalApprovedTestimonials,
        Double averageRecommendationScore,
        Integer recommendingPercent) {

    public AnalyticsSummaryDto {
        // Defensive copies (SpotBugs EI_EXPOSE_REP/EI_EXPOSE_REP2).
        testimonialsByCountry = List.copyOf(testimonialsByCountry);
        achievementCounts = List.copyOf(achievementCounts);
        testimonialCountsByTopic = List.copyOf(testimonialCountsByTopic);
    }
}
