package com.iitm.beacon.gallery;

import java.util.List;

/**
 * Full public testimonial detail (UC-EXPAND-TESTIMONIAL). Never includes
 * contact-method values or the submitter's email (decision 6) — only
 * {@code hasRevealableContact}. {@code displayName} (decision 10) appears
 * only here, never on {@link TestimonialCardDto}. {@code status} is always
 * {@code "APPROVED"} in practice, since {@code GalleryService.getDetail}
 * only ever returns approved testimonials to this endpoint.
 */
public record TestimonialDetailDto(
        Long id,
        String displayName,
        CountryDto country,
        Integer recommendationScore,
        String status,
        List<TestimonialSectionViewDto> sections,
        List<String> achievements,
        boolean hasRevealableContact) {
}
