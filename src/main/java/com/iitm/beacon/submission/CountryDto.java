package com.iitm.beacon.submission;

/**
 * Public shape of a {@code Country} reference row for the submission form's
 * country {@code <select>} (independent of {@code gallery.CountryDto} —
 * decision 9: no feature slice imports another).
 */
public record CountryDto(String code, String name) {
}
