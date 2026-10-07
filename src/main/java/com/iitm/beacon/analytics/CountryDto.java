package com.iitm.beacon.analytics;

/** A country on the dashboard ({@code Country} in api-spec): ISO 3166-1 alpha-2 code and name. */
public record CountryDto(String code, String name) {
}
