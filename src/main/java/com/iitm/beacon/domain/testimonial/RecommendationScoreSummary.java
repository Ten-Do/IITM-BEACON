package com.iitm.beacon.domain.testimonial;

/**
 * The recommendation-score figures of every testimonial in one status, as
 * one aggregate row (decision 30) — projection of {@link
 * TestimonialRepository#summarizeScoresByStatus}.
 *
 * @param testimonialCount how many testimonials are in the status
 * @param averageScore their mean score, unrounded; {@code null} when there are none
 * @param countAtOrAboveThreshold how many of them score at least the requested threshold
 */
public record RecommendationScoreSummary(long testimonialCount, Double averageScore, long countAtOrAboveThreshold) {
}
