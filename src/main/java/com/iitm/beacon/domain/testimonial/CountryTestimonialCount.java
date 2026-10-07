package com.iitm.beacon.domain.testimonial;

/**
 * How many testimonials in one status come from one country — projection of
 * {@link TestimonialRepository#countPerCountryByStatus}; never zero.
 */
public record CountryTestimonialCount(String countryCode, String countryName, long testimonialCount) {
}
