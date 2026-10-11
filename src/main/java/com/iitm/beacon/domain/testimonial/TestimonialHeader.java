package com.iitm.beacon.domain.testimonial;

import java.time.Instant;

/**
 * Every column of a testimonial a page shows, with its country's code and
 * name, but none of its parts — projection of {@link TestimonialRepository}'s
 * header queries, which select {@code email} through JPQL so it is
 * decrypted (decision 6).
 */
public record TestimonialHeader(
        Long id,
        String firstName,
        String lastName,
        String rollNumber,
        Integer admissionYear,
        String email,
        String countryCode,
        String countryName,
        Integer recommendationScore,
        TestimonialStatus status,
        boolean dataProcessingConsent,
        boolean identityModified,
        boolean scoreModified,
        Instant createdAt,
        Instant reviewedAt) {
}
