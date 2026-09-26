package com.iitm.beacon.moderation;

/**
 * An achievement as seen by the admin queue page — unlike {@link
 * ModerationTestimonialDetailDto#achievements()} (slugs only, for the REST
 * API consumer to resolve itself), this pairs the slug with its
 * human-readable {@code label} for direct rendering.
 */
public record ModerationAchievementViewDto(String slug, String label) {
}
