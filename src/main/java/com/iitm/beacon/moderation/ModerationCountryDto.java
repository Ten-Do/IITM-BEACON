package com.iitm.beacon.moderation;

/**
 * Nested country shape for {@link ModerationTestimonialDetailDto}, matching
 * the {@code Country} schema in {@code docs/api-spec.yaml} — independent of
 * any other slice's DTOs (decision 9).
 */
public record ModerationCountryDto(String code, String name) {
}
