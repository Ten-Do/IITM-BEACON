package com.iitm.beacon.moderation;

import java.util.List;

/**
 * One filled section as seen by the admin (UC-VIEW-PENDING-QUEUE). {@code
 * updated} mirrors {@code TestimonialSection.modified} (decision 18).
 */
public record ModerationSectionViewDto(
        String topicSlug, String topicLabel, String answer, List<ModerationPhotoRefDto> photos, boolean updated) {
}
