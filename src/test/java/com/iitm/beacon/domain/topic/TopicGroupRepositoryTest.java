package com.iitm.beacon.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TopicGroupRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Test
    void savedTopicGroup_roundTrips() {
        TopicGroup group = TopicGroup.builder().label("Academics").displayOrder(1).build();

        TopicGroup saved = topicGroupRepository.saveAndFlush(group);

        assertThat(saved.getId()).isNotNull();
        var found = topicGroupRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getLabel()).isEqualTo("Academics");
    }

    @Test
    void active_defaultsToTrue_whenNotExplicitlySet() {
        TopicGroup group = TopicGroup.builder().label("Travel").displayOrder(2).build();

        TopicGroup saved = topicGroupRepository.saveAndFlush(group);

        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertThat(topicGroupRepository.findById(-1L)).isEmpty();
    }
}
