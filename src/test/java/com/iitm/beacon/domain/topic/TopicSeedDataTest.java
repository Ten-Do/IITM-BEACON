package com.iitm.beacon.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the V13 Flyway seed migration (decision 11) — the topic catalog
 * transcribed from docs/use-cases.md: 9 topic groups (38 subtopics) plus 8
 * standalone topics, 46 topics total.
 */
class TopicSeedDataTest extends AbstractRepositoryTest {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Test
    void findBySlug_general_findsTheMandatoryStandaloneCatchAll() {
        var found = topicRepository.findBySlug("general");

        assertThat(found).isPresent();
        assertThat(found.get().getTopicGroup()).isNull();
    }

    @Test
    void generalSlugConstant_matchesTheSeededStandaloneTopic() {
        var found = topicRepository.findBySlug(Topic.GENERAL_SLUG);

        assertThat(found).isPresent();
        assertThat(found.get().getTopicGroup()).isNull();
    }

    @Test
    void seededTopicGroupCount_isNine() {
        assertThat(topicGroupRepository.count()).isEqualTo(9);
    }

    @Test
    void seededTopicCount_is46() {
        assertThat(topicRepository.count()).isEqualTo(46);
    }
}
