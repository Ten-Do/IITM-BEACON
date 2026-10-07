package com.iitm.beacon.analytics;

/**
 * An achievement on the dashboard ({@code Achievement} in api-spec). Only
 * active achievements are counted (decision 30), so {@code active} is always
 * {@code true} here; it stays in the shape the spec shares with the catalog.
 */
public record AchievementDto(Long id, String slug, String label, int displayOrder, boolean active) {
}
