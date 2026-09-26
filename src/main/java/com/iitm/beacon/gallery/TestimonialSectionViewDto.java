package com.iitm.beacon.gallery;

import java.util.List;

/**
 * One filled topic section within {@link TestimonialDetailDto} (decision
 * 12). {@code updated} mirrors {@code TestimonialSection.modified} (decision
 * 18), driving the public "updated since last approval" badge.
 */
public record TestimonialSectionViewDto(
        String topicSlug, String topicLabel, String answer, List<PhotoRefDto> photos, boolean updated) {
}
