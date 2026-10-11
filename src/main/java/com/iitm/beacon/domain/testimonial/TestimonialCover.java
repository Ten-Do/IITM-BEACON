package com.iitm.beacon.domain.testimonial;

/**
 * What a testimonial's card shows of it: the start of its first visible
 * section's answer and that section's first photo — projection of {@link
 * TestimonialSectionRepository#findCoversByTestimonialIdIn}. Both photo
 * paths are {@code null} when that section has no photo; only the
 * thumbnail's is for a legacy photo stored before thumbnails existed.
 */
public record TestimonialCover(
        Long testimonialId, String answerStart, String photoFilePath, String photoThumbnailPath) {
}
