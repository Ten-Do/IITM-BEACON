package com.iitm.beacon.domain.testimonial;

/**
 * One section of a visible topic (decision 28) with what its topic is shown
 * by — projection of {@link TestimonialSectionRepository#findVisibleByTestimonialIdIn}.
 * {@code modified} is the section's own flag (decision 18).
 */
public record VisibleSection(
        Long id, Long testimonialId, String topicSlug, String topicLabel, String answerText, boolean modified) {
}
