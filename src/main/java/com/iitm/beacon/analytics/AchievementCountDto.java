package com.iitm.beacon.analytics;

/** How many approved testimonials ticked one active achievement (decision 30); {@code count} is at least 1. */
public record AchievementCountDto(AchievementDto achievement, long count) {
}
