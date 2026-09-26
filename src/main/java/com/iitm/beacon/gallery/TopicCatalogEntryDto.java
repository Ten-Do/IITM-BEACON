package com.iitm.beacon.gallery;

import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import java.util.List;

/**
 * One top-level topic-catalog pick (decision 11) offered by {@code
 * GET /api/gallery/topics} — either a topic group with its qualifying
 * subtopics nested ({@code kind = GROUP}), or a standalone topic
 * ({@code kind = STANDALONE}). Flat record with two factory methods rather
 * than a sealed hierarchy, matching the single flat {@code TopicCatalogEntry}
 * schema in {@code api-spec.yaml}.
 */
public record TopicCatalogEntryDto(
        String kind,
        String label,
        Long groupId,
        List<TopicPickDto> subtopics,
        Long topicId,
        String slug,
        String guidingPrompt) {

    public static TopicCatalogEntryDto group(TopicGroup group, List<TopicPickDto> subtopics) {
        return new TopicCatalogEntryDto("GROUP", group.getLabel(), group.getId(), subtopics, null, null, null);
    }

    public static TopicCatalogEntryDto standalone(Topic topic) {
        return new TopicCatalogEntryDto(
                "STANDALONE", topic.getLabel(), null, null, topic.getId(), topic.getSlug(), topic.getGuidingPrompt());
    }
}
