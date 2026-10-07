package com.iitm.beacon.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TopicGroupRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

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

    // -- findAllByOrderByDisplayOrderAscIdAsc: the admin catalog list (display order, then id) --

    @Test
    void findAllOrdered_sortsByDisplayOrder_thenBreaksTiesById() {
        TopicGroup late = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Late").displayOrder(9999).build());
        TopicGroup tieFirst = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Tie A").displayOrder(0).build());
        TopicGroup tieSecond = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Tie B").displayOrder(0).build());
        Set<Long> fixtureIds = Set.of(late.getId(), tieFirst.getId(), tieSecond.getId());

        List<TopicGroup> all = topicGroupRepository.findAllByOrderByDisplayOrderAscIdAsc();

        assertThat(all).isSortedAccordingTo(
                Comparator.comparing(TopicGroup::getDisplayOrder).thenComparing(TopicGroup::getId));
        assertThat(all.stream().map(TopicGroup::getId).filter(fixtureIds::contains))
                .containsExactly(tieFirst.getId(), tieSecond.getId(), late.getId());
    }
}
