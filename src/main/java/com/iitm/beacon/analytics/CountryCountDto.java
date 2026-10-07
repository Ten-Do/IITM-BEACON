package com.iitm.beacon.analytics;

/** How many approved testimonials come from one country (decision 30); {@code count} is at least 1. */
public record CountryCountDto(CountryDto country, long count) {
}
