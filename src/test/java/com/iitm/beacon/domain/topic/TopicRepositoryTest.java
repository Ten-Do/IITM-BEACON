package com.iitm.beacon.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Generic repository CRUD/constraint behavior, exercised with
 * {@code fixture_}-prefixed slugs so these tests are independent of the
 * seeded topic catalog verified separately in {@link TopicSeedDataTest}.
 */
class TopicRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Test
    void standaloneTopic_withNullTopicGroup_persistsAndLoadsCorrectly() {
        Topic standalone = Topic.builder()
                .topicGroup(null)
                .slug("fixture_standalone")
                .label("Fixture Standalone")
                .guidingPrompt("Anything else you'd like to add.")
                .displayOrder(1)
                .build();

        Topic saved = topicRepository.saveAndFlush(standalone);
        topicRepository.flush();

        Topic reloaded = topicRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTopicGroup()).isNull();
        assertThat(reloaded.getSlug()).isEqualTo("fixture_standalone");
    }

    @Test
    void groupedTopic_persistsWithItsTopicGroup() {
        TopicGroup group = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Group").displayOrder(1).build());

        Topic topic = Topic.builder()
                .topicGroup(group)
                .slug("fixture_grouped_topic")
                .label("Fixture Grouped Topic")
                .guidingPrompt("A fixture guiding prompt?")
                .displayOrder(1)
                .build();
        Topic saved = topicRepository.saveAndFlush(topic);

        Topic reloaded = topicRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTopicGroup()).isNotNull();
        assertThat(reloaded.getTopicGroup().getId()).isEqualTo(group.getId());
    }

    @Test
    void findBySlug_existingSlug_returnsTopic() {
        topicRepository.saveAndFlush(Topic.builder()
                .slug("fixture_findable")
                .label("Fixture Findable")
                .guidingPrompt("A fixture guiding prompt?")
                .displayOrder(1)
                .build());

        var found = topicRepository.findBySlug("fixture_findable");

        assertThat(found).isPresent();
    }

    @Test
    void findBySlug_nonExistentSlug_returnsEmpty() {
        assertThat(topicRepository.findBySlug("does-not-exist")).isEmpty();
    }

    @Test
    void duplicateSlug_violatesUniqueConstraint() {
        topicRepository.saveAndFlush(Topic.builder()
                .slug("fixture_dup_slug")
                .label("Label one")
                .guidingPrompt("Prompt one")
                .displayOrder(1)
                .build());

        assertThatThrownBy(() -> topicRepository.saveAndFlush(Topic.builder()
                        .slug("fixture_dup_slug")
                        .label("Label two")
                        .guidingPrompt("Prompt two")
                        .displayOrder(2)
                        .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void active_defaultsToTrue_whenNotExplicitlySet() {
        Topic saved = topicRepository.saveAndFlush(Topic.builder()
                .slug("fixture_active_default")
                .label("Label")
                .guidingPrompt("Prompt")
                .displayOrder(1)
                .build());

        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void findByTopicGroupId_groupWithTopics_returnsOnlyThatGroupsTopics() {
        TopicGroup group = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Group For Lookup").displayOrder(1).build());
        TopicGroup otherGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Other Group").displayOrder(2).build());
        Topic first = topicRepository.saveAndFlush(Topic.builder()
                .topicGroup(group)
                .slug("fixture_group_lookup_a")
                .label("A")
                .guidingPrompt("Prompt A")
                .displayOrder(1)
                .build());
        Topic second = topicRepository.saveAndFlush(Topic.builder()
                .topicGroup(group)
                .slug("fixture_group_lookup_b")
                .label("B")
                .guidingPrompt("Prompt B")
                .displayOrder(2)
                .build());
        topicRepository.saveAndFlush(Topic.builder()
                .topicGroup(otherGroup)
                .slug("fixture_group_lookup_c")
                .label("C")
                .guidingPrompt("Prompt C")
                .displayOrder(1)
                .build());

        var found = topicRepository.findByTopicGroupId(group.getId());

        assertThat(found).extracting(Topic::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void findByTopicGroupId_groupWithNoTopics_returnsEmptyList() {
        TopicGroup emptyGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fixture Empty Group").displayOrder(3).build());

        var found = topicRepository.findByTopicGroupId(emptyGroup.getId());

        assertThat(found).isEmpty();
    }

    // -- findAllByOrderByDisplayOrderAscIdAsc: the admin catalog list (display order, then id) --

    @Test
    void findAllOrdered_sortsByDisplayOrder_thenBreaksTiesById() {
        Topic late = topicRepository.saveAndFlush(topic(null, "fixture_order_late", 9999, true));
        Topic tieFirst = topicRepository.saveAndFlush(topic(null, "fixture_order_tie_a", 0, true));
        Topic tieSecond = topicRepository.saveAndFlush(topic(null, "fixture_order_tie_b", 0, true));
        Set<Long> fixtureIds = Set.of(late.getId(), tieFirst.getId(), tieSecond.getId());

        List<Topic> all = topicRepository.findAllByOrderByDisplayOrderAscIdAsc();

        assertThat(all).isSortedAccordingTo(Comparator.comparing(Topic::getDisplayOrder).thenComparing(Topic::getId));
        assertThat(all.stream().map(Topic::getId).filter(fixtureIds::contains))
                .containsExactly(tieFirst.getId(), tieSecond.getId(), late.getId());
    }

    private static Topic topic(TopicGroup group, String slug, int displayOrder, boolean active) {
        return Topic.builder()
                .topicGroup(group)
                .slug(slug)
                .label("Fixture " + slug)
                .guidingPrompt("A fixture guiding prompt?")
                .displayOrder(displayOrder)
                .active(active)
                .build();
    }
}
