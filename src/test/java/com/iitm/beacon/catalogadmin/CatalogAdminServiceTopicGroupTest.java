package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CatalogAdminService}'s topic-group reads and writes
 * (UC-MANAGE-TOPIC-GROUPS, decision 28): list (inactive ones included, in
 * display order then id), get, create, and partial update. Deletes are in
 * {@code CatalogAdminServiceDeleteTest}.
 */
@SpringBootTest
@Transactional
class CatalogAdminServiceTopicGroupTest {

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    private CatalogAdminService service;
    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        service = new CatalogAdminService(
                topicGroupRepository,
                topicRepository,
                achievementRepository,
                testimonialRepository,
                testimonialSectionRepository,
                testimonialAchievementRepository,
                mock(PhotoFileDeleter.class));
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
    }

    // -- list --

    @Test
    void list_returnsEveryGroupIncludingInactiveOnes_inDisplayOrderThenId() {
        TopicGroup inactiveFirst = fixtures.group("Inactive first", 0, false);
        TopicGroup tieA = fixtures.group("Tie A", 7000, true);
        TopicGroup tieB = fixtures.group("Tie B", 7000, false);
        TopicGroup last = fixtures.group("Last", 9999, true);

        List<TopicGroupDto> groups = service.listTopicGroups();

        assertThat(groups).hasSize((int) topicGroupRepository.count());
        assertThat(groups.get(0)).isEqualTo(new TopicGroupDto(inactiveFirst.getId(), "Inactive first", 0, false));
        assertThat(groups).extracting(TopicGroupDto::id)
                .containsSubsequence(tieA.getId(), tieB.getId(), last.getId())
                .endsWith(last.getId());
        assertThat(groups).extracting(TopicGroupDto::displayOrder).isSorted();
    }

    // -- get --

    @Test
    void get_returnsTheGroup() {
        TopicGroup group = fixtures.group("Sports", 12, false);

        assertThat(service.getTopicGroup(group.getId()))
                .isEqualTo(new TopicGroupDto(group.getId(), "Sports", 12, false));
    }

    @Test
    void get_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.getTopicGroup(987_654L)).isInstanceOf(NotFoundException.class);
    }

    // -- create --

    @Test
    void create_savesAnActiveGroupAndReturnsIt() {
        TopicGroupDto created = service.createTopicGroup(new TopicGroupCreateRequest("  Sports  ", 0));

        assertThat(created.id()).isNotNull();
        assertThat(created.label()).isEqualTo("Sports");
        assertThat(created.displayOrder()).isZero();
        assertThat(created.active()).isTrue();
        TopicGroup stored = topicGroupRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getLabel()).isEqualTo("Sports");
        assertThat(stored.isActive()).isTrue();
    }

    @Test
    void create_withTheSameLabelAndOrderAsAnExistingGroup_isAllowed() {
        // Labels are not unique, and display orders may repeat (decision 28).
        TopicGroupDto first = service.createTopicGroup(new TopicGroupCreateRequest("Academics", 1));

        assertThat(first.id()).isNotEqualTo(1L);
        assertThat(service.listTopicGroups()).filteredOn(g -> g.label().equals("Academics")).hasSize(2);
    }

    // -- patch --

    @Test
    void patch_changesOnlyThePresentFields() {
        TopicGroup group = fixtures.group("Old", 3, true);

        TopicGroupDto renamed = service.patchTopicGroup(group.getId(), new TopicGroupPatchRequest(" New ", null, null));
        assertThat(renamed).isEqualTo(new TopicGroupDto(group.getId(), "New", 3, true));

        TopicGroupDto reordered = service.patchTopicGroup(group.getId(), new TopicGroupPatchRequest(null, 9999, null));
        assertThat(reordered).isEqualTo(new TopicGroupDto(group.getId(), "New", 9999, true));

        TopicGroupDto deactivated =
                service.patchTopicGroup(group.getId(), new TopicGroupPatchRequest(null, null, false));
        assertThat(deactivated).isEqualTo(new TopicGroupDto(group.getId(), "New", 9999, false));

        TopicGroupDto reactivated =
                service.patchTopicGroup(group.getId(), new TopicGroupPatchRequest(null, null, true));
        assertThat(reactivated.active()).isTrue();
        assertThat(topicGroupRepository.findById(group.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void patch_withNoFields_changesNothing() {
        TopicGroup group = fixtures.group("Same", 4, false);

        TopicGroupDto result = service.patchTopicGroup(group.getId(), new TopicGroupPatchRequest(null, null, null));

        assertThat(result).isEqualTo(new TopicGroupDto(group.getId(), "Same", 4, false));
    }

    @Test
    void patch_deactivatingAGroup_leavesItsTopicsOwnFlagAlone() {
        // The group's topics become invisible through the group (Topic.isVisible), not by being deactivated.
        TopicGroup group = fixtures.group("G", 1, true);
        var topic = fixtures.topic(group, "T", 1, true);

        service.patchTopicGroup(group.getId(), new TopicGroupPatchRequest(null, null, false));

        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isActive()).isTrue();
        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isVisible()).isFalse();
    }

    @Test
    void patch_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.patchTopicGroup(987_654L, new TopicGroupPatchRequest("X", null, null)))
                .isInstanceOf(NotFoundException.class);
    }
}
