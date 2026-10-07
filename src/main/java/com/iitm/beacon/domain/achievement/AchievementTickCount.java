package com.iitm.beacon.domain.achievement;

/**
 * How many testimonials in one status ticked one active achievement —
 * projection of {@link TestimonialAchievementRepository#countTicksPerActiveAchievementByStatus};
 * never zero.
 */
public record AchievementTickCount(
        Long achievementId, String slug, String label, Integer displayOrder, boolean active, long tickCount) {
}
