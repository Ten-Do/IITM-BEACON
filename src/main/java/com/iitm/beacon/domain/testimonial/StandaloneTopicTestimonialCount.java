package com.iitm.beacon.domain.testimonial;

/**
 * How many testimonials in one status have at least one section in one
 * visible standalone topic — projection of {@link
 * TestimonialSectionRepository#countTestimonialsPerVisibleStandaloneTopic};
 * never zero. {@code displayOrder} is the topic's own, on the top-level range
 * it shares with topic groups.
 */
public record StandaloneTopicTestimonialCount(Long topicId, String label, Integer displayOrder, long testimonialCount) {
}
