package com.iitm.beacon.domain.testimonial;

/**
 * The few columns a list entry needs of a testimonial: its id, its
 * country and its score — projection of {@link
 * TestimonialSummaryQueries#findSummaries}.
 */
public record TestimonialSummary(Long id, String countryCode, String countryName, Integer recommendationScore) {
}
