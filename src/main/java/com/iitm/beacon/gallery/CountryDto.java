package com.iitm.beacon.gallery;

/**
 * Public shape of a {@code Country} reference row (decision 1), used both as
 * {@code TestimonialCardDto}/{@code TestimonialDetailDto}'s embedded country
 * and as the {@code GET /api/gallery/countries} filter-dropdown listing.
 */
public record CountryDto(String code, String name) {
}
