package com.iitm.beacon.submission;

/**
 * One selectable subtopic nested inside a {@link TopicCatalogEntryDto} of
 * kind {@code GROUP} (independent of {@code gallery.TopicPickDto} —
 * decision 9: no feature slice imports another).
 */
public record TopicPickDto(Long id, String slug, String label, String guidingPrompt) {
}
