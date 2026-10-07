package com.iitm.beacon.catalogadmin;

/**
 * A topic as the catalog admin sees it ({@code Topic} in api-spec), inactive
 * ones included. {@code topicGroupId} is {@code null} for a standalone topic
 * (decision 11).
 */
public record TopicDto(
        Long id,
        Long topicGroupId,
        String slug,
        String label,
        String guidingPrompt,
        Integer displayOrder,
        boolean active) {
}
