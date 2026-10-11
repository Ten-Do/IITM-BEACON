package com.iitm.beacon.domain.testimonial;

/**
 * One contact method of a testimonial, its {@code value} decrypted
 * (decision 5) — projection of {@link ContactMethodRepository}'s queries,
 * which select it through JPQL so the column's converter applies.
 */
public record ContactMethodValue(Long testimonialId, String typeSlug, String value, boolean isPublic) {
}
