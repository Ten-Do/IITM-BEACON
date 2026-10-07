package com.iitm.beacon.catalogadmin;

/** An achievement as the catalog admin sees it ({@code Achievement} in api-spec), inactive ones included. */
public record AchievementDto(Long id, String slug, String label, Integer displayOrder, boolean active) {
}
