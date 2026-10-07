package com.iitm.beacon.domain.testimonial;

/**
 * How many testimonials in one status have at least one section in a visible
 * topic of one active topic group — projection of {@link
 * TestimonialSectionRepository#countTestimonialsPerActiveTopicGroup}; never
 * zero. {@code displayOrder} is the group's own, on the top-level range it
 * shares with standalone topics.
 */
public record TopicGroupTestimonialCount(Long groupId, String label, Integer displayOrder, long testimonialCount) {
}
