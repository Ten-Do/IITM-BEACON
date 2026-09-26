package com.iitm.beacon.moderation;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import java.time.Instant;
import java.util.List;

/**
 * View model for one card on the admin queue page ({@code
 * moderation/queue.html}, UC-VIEW-PENDING-QUEUE). Same underlying data as
 * {@link ModerationTestimonialDetailDto}, but shaped for direct Thymeleaf
 * rendering rather than JSON: achievements carry their label (not just the
 * slug), plus {@code createdAt} and a {@code resubmitted} flag (true once the
 * testimonial has been reviewed before — i.e. {@code reviewedAt != null} —
 * meaning this is a reject-then-resubmit or approve-then-edit case, not a
 * brand new submission).
 */
public record ModerationQueueCardDto(
        Long id,
        String firstName,
        String lastName,
        String rollNumber,
        Integer admissionYear,
        String email,
        ModerationCountryDto country,
        Integer recommendationScore,
        TestimonialStatus status,
        List<ModerationSectionViewDto> sections,
        List<ModerationAchievementViewDto> achievements,
        List<ModerationContactMethodViewDto> contactMethods,
        boolean identityModified,
        boolean scoreModified,
        Instant createdAt,
        boolean resubmitted) {
}
