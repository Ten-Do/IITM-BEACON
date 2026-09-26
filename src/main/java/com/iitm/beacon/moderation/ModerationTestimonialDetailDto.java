package com.iitm.beacon.moderation;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import java.util.List;

/**
 * Full detail as seen by the admin (UC-VIEW-PENDING-QUEUE) — includes
 * identity fields, the decrypted email, every contact method (decisions 6,
 * 10), and the admin-only diff flags (decision 18).
 */
public record ModerationTestimonialDetailDto(
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
        List<String> achievements,
        List<ModerationContactMethodViewDto> contactMethods,
        boolean identityModified,
        boolean scoreModified) {
}
