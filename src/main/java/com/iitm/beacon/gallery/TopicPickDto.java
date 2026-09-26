package com.iitm.beacon.gallery;

/** One selectable subtopic nested inside a {@link TopicCatalogEntryDto} of kind {@code GROUP}. */
public record TopicPickDto(Long id, String slug, String label, String guidingPrompt) {
}
