package com.iitm.beacon.submission;

/**
 * Public shape for {@code GET /submissions/achievements} (decision 20) —
 * matches the {@code AchievementPick} schema in {@code api-spec.yaml}.
 */
public record AchievementView(String slug, String label) {
}
