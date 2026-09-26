package com.iitm.beacon.submission;

import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import java.util.List;

/**
 * One top-level topic-catalog pick (decision 11) offered by the submission
 * form's topic picker — either a topic group with its active subtopics
 * nested ({@code kind = GROUP}), or a standalone topic ({@code kind =
 * STANDALONE}). Independent of {@code gallery.TopicCatalogEntryDto}
 * (decision 9: no feature slice imports another) — this copy is filtered by
 * "active" alone, never by approved-testimonial existence.
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
