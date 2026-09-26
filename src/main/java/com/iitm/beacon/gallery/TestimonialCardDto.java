package com.iitm.beacon.gallery;

/**
 * Shape returned by the gallery list endpoint (UC-BROWSE-APPROVED).
 * Deliberately carries no name-derived field at all (decision 10) —
 * {@code previewText}/{@code thumbnailUrl} are drawn from the testimonial's
 * first filled section, in display order.
 */
public record TestimonialCardDto(
        Long id, CountryDto country, Integer recommendationScore, String previewText, String thumbnailUrl) {
}
