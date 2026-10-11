package com.iitm.beacon.domain.achievement;

/**
 * One testimonial's tick of one visible achievement (decision 28) —
 * projection of {@link TestimonialAchievementRepository#findVisibleTicksByTestimonialIdIn}.
 */
public record AchievementTick(Long testimonialId, String slug, String label) {
}
