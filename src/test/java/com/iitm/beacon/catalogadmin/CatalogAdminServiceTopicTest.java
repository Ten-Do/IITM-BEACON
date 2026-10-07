package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.common.error.CatalogValidationException;
import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CatalogAdminService}'s topic reads and writes (UC-MANAGE-TOPICS,
 * decision 28): list, get, create (standalone or in a group), partial update
 * with re-parenting, slug uniqueness (409) on create and on edit, an unknown
 * {@code topicGroupId} (400), and the protected {@code general} topic.
 * Deletes are in {@code CatalogAdminServiceDeleteTest}.
 */
@SpringBootTest
@Transactional
class CatalogAdminServiceTopicTest {

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

    private static TopicPatchRequest patch() {
        return new TopicPatchRequest(null, null, null, null, null, null);
    }

    private static TopicPatchRequest patchSlug(String slug) {
        return new TopicPatchRequest(slug, null, null, null, null, null);
    }

    private static TopicPatchRequest patchActive(boolean active) {
        return new TopicPatchRequest(null, null, null, null, active, null);
    }

    private static TopicPatchRequest patchGroup(Optional<Long> topicGroupId) {
        return new TopicPatchRequest(null, null, null, null, null, topicGroupId);
    }

    private static TopicDto dto(Topic topic) {
        return new TopicDto(
                topic.getId(),
                topic.getTopicGroup() == null ? null : topic.getTopicGroup().getId(),
                topic.getSlug(),
                topic.getLabel(),
                topic.getGuidingPrompt(),
                topic.getDisplayOrder(),
                topic.isActive());
    }

    // -- list / get --

    @Test
    void list_returnsEveryTopicIncludingInactiveOnes_inDisplayOrderThenId() {
        Topic inactive = fixtures.topic(null, "Hidden", 0, false);

        List<TopicDto> topics = service.listTopics();

        assertThat(topics).hasSize((int) topicRepository.count());
        assertThat(topics).contains(dto(inactive));
        assertThat(topics).extracting(TopicDto::displayOrder).isSorted();
        assertThat(topics).filteredOn(t -> t.slug().equals("general"))
                .singleElement()
                .satisfies(general -> assertThat(general.topicGroupId()).isNull());
    }

    @Test
    void get_returnsTheTopicWithItsGroupId() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 2, false);

        assertThat(service.getTopic(topic.getId())).isEqualTo(dto(topic));
        assertThat(service.getTopic(topic.getId()).topicGroupId()).isEqualTo(group.getId());
    }

    @Test
    void get_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.getTopic(987_654L)).isInstanceOf(NotFoundException.class);
    }

    // -- create --

    @Test
    void create_standalone_savesAnActiveTopicWithoutGroup() {
        TopicDto created =
                service.createTopic(new TopicCreateRequest("sports_x", " Sports ", " Any sports? ", 5, null));

        assertThat(created.id()).isNotNull();
        assertThat(created).isEqualTo(new TopicDto(created.id(), null, "sports_x", "Sports", "Any sports?", 5, true));
        Topic stored = topicRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getTopicGroup()).isNull();
        assertThat(stored.isActive()).isTrue();
    }

    @Test
    void create_inAGroup_linksTheGroup() {
        TopicGroup group = fixtures.group("G", 1, true);

        TopicDto created = service.createTopic(new TopicCreateRequest("in_group_x", "L", "P", 1, group.getId()));

        assertThat(created.topicGroupId()).isEqualTo(group.getId());
        assertThat(topicRepository.findById(created.id()).orElseThrow().getTopicGroup().getId())
                .isEqualTo(group.getId());
    }

    @Test
    void create_inAnInactiveGroup_isAllowed_andTheTopicIsInvisible() {
        TopicGroup group = fixtures.group("Off", 1, false);

        TopicDto created = service.createTopic(new TopicCreateRequest("in_off_group", "L", "P", 1, group.getId()));

        Topic stored = topicRepository.findById(created.id()).orElseThrow();
        assertThat(stored.isActive()).isTrue();
        assertThat(stored.isVisible()).isFalse();
    }

    @Test
    void create_withAnUnknownGroup_throwsValidationOnTopicGroupId_andSavesNothing() {
        long before = topicRepository.count();

        assertThatThrownBy(() -> service.createTopic(new TopicCreateRequest("orphan_x", "L", "P", 1, 987_654L)))
                .isInstanceOf(CatalogValidationException.class)
                .satisfies(ex -> assertThat(((CatalogValidationException) ex).getViolations())
                        .extracting(FieldViolation::field)
                        .containsExactly("topicGroupId"));
        assertThat(topicRepository.count()).isEqualTo(before);
    }

    @Test
    void create_withASlugAlreadyUsed_throwsConflictOnSlug() {
        assertThatThrownBy(() -> service.createTopic(new TopicCreateRequest("academics_teaching", "L", "P", 1, null)))
                .isInstanceOf(CatalogConflictException.class)
                .satisfies(ex -> assertThat(((CatalogConflictException) ex).getField()).contains("slug"));
    }

    @Test
    void create_withTheGeneralSlug_isADuplicate() {
        assertThatThrownBy(() -> service.createTopic(new TopicCreateRequest("general", "L", "P", 1, null)))
                .isInstanceOf(CatalogConflictException.class);
    }

    @Test
    void create_withTheSlugOfAnInactiveTopic_isStillADuplicate() {
        Topic inactive = fixtures.topic(null, "Off", 1, false);

        assertThatThrownBy(() -> service.createTopic(new TopicCreateRequest(inactive.getSlug(), "L", "P", 1, null)))
                .isInstanceOf(CatalogConflictException.class);
    }

    @Test
    void create_withAnUnknownGroupAndADuplicateSlug_reportsTheUnknownGroupFirst() {
        assertThatThrownBy(() -> service.createTopic(new TopicCreateRequest("general", "L", "P", 1, 987_654L)))
                .isInstanceOf(CatalogValidationException.class);
    }

    // -- patch: fields --

    @Test
    void patch_changesOnlyThePresentFields() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "Old", 3, true);

        TopicDto result = service.patchTopic(
                topic.getId(), new TopicPatchRequest(null, " New ", " New prompt ", 0, null, null));

        assertThat(result).isEqualTo(new TopicDto(
                topic.getId(), group.getId(), topic.getSlug(), "New", "New prompt", 0, true));
    }

    @Test
    void patch_withNoFields_changesNothing() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "Same", 3, false);
        TopicDto before = dto(topic);

        assertThat(service.patchTopic(topic.getId(), patch())).isEqualTo(before);
    }

    @Test
    void patch_deactivateAndReactivate() {
        Topic topic = fixtures.topic(null, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchActive(false)).active()).isFalse();
        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isActive()).isFalse();
        assertThat(service.patchTopic(topic.getId(), patchActive(true)).active()).isTrue();
    }

    @Test
    void patch_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.patchTopic(987_654L, patchSlug("x"))).isInstanceOf(NotFoundException.class);
    }

    // -- patch: slug --

    @Test
    void patch_slugToANewUniqueValue_changesIt() {
        Topic topic = fixtures.topic(null, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchSlug("renamed_slug_x")).slug()).isEqualTo("renamed_slug_x");
        assertThat(topicRepository.findBySlug("renamed_slug_x")).isPresent();
    }

    @Test
    void patch_slugToItsOwnCurrentValue_isNotAConflict() {
        Topic topic = fixtures.topic(null, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchSlug(topic.getSlug())).slug()).isEqualTo(topic.getSlug());
    }

    @Test
    void patch_slugToAnotherTopicsSlug_throwsConflictAndChangesNothing() {
        Topic topic = fixtures.topic(null, "T", 1, true);
        Topic other = fixtures.topic(null, "Other", 1, false);

        assertThatThrownBy(() -> service.patchTopic(
                        topic.getId(), new TopicPatchRequest(other.getSlug(), "Changed", null, null, null, null)))
                .isInstanceOf(CatalogConflictException.class)
                .satisfies(ex -> assertThat(((CatalogConflictException) ex).getField()).contains("slug"));
        assertThat(topicRepository.findById(topic.getId()).orElseThrow().getLabel()).isEqualTo("T");
    }

    @Test
    void patch_slugToGeneral_onAnotherTopic_throwsConflict() {
        Topic topic = fixtures.topic(null, "T", 1, true);

        assertThatThrownBy(() -> service.patchTopic(topic.getId(), patchSlug("general")))
                .isInstanceOf(CatalogConflictException.class);
    }

    @Test
    void patch_slugAnAchievementAlreadyUses_isFine_slugsAreUniquePerTable() {
        Topic topic = fixtures.topic(null, "T", 1, true);
        String achievementSlug = fixtures.achievement("A", 1, true).getSlug();

        assertThat(service.patchTopic(topic.getId(), patchSlug(achievementSlug)).slug()).isEqualTo(achievementSlug);
    }

    // -- patch: re-parenting --

    @Test
    void patch_topicGroupIdAbsent_leavesTheGroupAsIs() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchSlug(topic.getSlug())).topicGroupId())
                .isEqualTo(group.getId());
    }

    @Test
    void patch_topicGroupIdEmpty_makesTheTopicStandalone() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchGroup(Optional.empty())).topicGroupId()).isNull();
        assertThat(topicRepository.findById(topic.getId()).orElseThrow().getTopicGroup()).isNull();
    }

    @Test
    void patch_topicGroupIdEmpty_onAStandaloneTopic_isANoOp() {
        Topic topic = fixtures.topic(null, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchGroup(Optional.empty())).topicGroupId()).isNull();
    }

    @Test
    void patch_topicGroupId_movesAStandaloneTopicIntoAGroup_andBetweenGroups() {
        TopicGroup first = fixtures.group("First", 1, true);
        TopicGroup second = fixtures.group("Second", 2, true);
        Topic topic = fixtures.topic(null, "T", 1, true);

        assertThat(service.patchTopic(topic.getId(), patchGroup(Optional.of(first.getId()))).topicGroupId())
                .isEqualTo(first.getId());
        assertThat(service.patchTopic(topic.getId(), patchGroup(Optional.of(second.getId()))).topicGroupId())
                .isEqualTo(second.getId());
    }

    @Test
    void patch_movingIntoAnInactiveGroup_isAllowed_andHidesTheTopic() {
        TopicGroup inactive = fixtures.group("Off", 1, false);
        Topic topic = fixtures.topic(null, "T", 1, true);

        TopicDto result = service.patchTopic(topic.getId(), patchGroup(Optional.of(inactive.getId())));

        assertThat(result.topicGroupId()).isEqualTo(inactive.getId());
        assertThat(result.active()).isTrue();
        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isVisible()).isFalse();
    }

    @Test
    void patch_toAnUnknownGroup_throwsValidationOnTopicGroupId_andChangesNothing() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 1, true);

        assertThatThrownBy(() -> service.patchTopic(
                        topic.getId(), new TopicPatchRequest(null, "Changed", null, null, null,
                                Optional.of(987_654L))))
                .isInstanceOf(CatalogValidationException.class)
                .satisfies(ex -> assertThat(((CatalogValidationException) ex).getViolations())
                        .extracting(FieldViolation::field)
                        .containsExactly("topicGroupId"));
        Topic stored = topicRepository.findById(topic.getId()).orElseThrow();
        assertThat(stored.getLabel()).isEqualTo("T");
        assertThat(stored.getTopicGroup().getId()).isEqualTo(group.getId());
    }

    // -- general --

    @Test
    void general_cannotBeDeactivated() {
        Topic general = fixtures.general();

        assertThatThrownBy(() -> service.patchTopic(general.getId(), patchActive(false)))
                .isInstanceOf(CatalogConflictException.class)
                .satisfies(ex -> assertThat(((CatalogConflictException) ex).getField()).isEmpty());
        assertThat(topicRepository.findById(general.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void general_cannotBeMovedIntoAGroup() {
        Topic general = fixtures.general();
        TopicGroup group = fixtures.group("G", 1, true);

        assertThatThrownBy(() -> service.patchTopic(general.getId(), patchGroup(Optional.of(group.getId()))))
                .isInstanceOf(CatalogConflictException.class);
        assertThat(topicRepository.findById(general.getId()).orElseThrow().getTopicGroup()).isNull();
    }

    @Test
    void general_cannotBeReslugged() {
        Topic general = fixtures.general();

        assertThatThrownBy(() -> service.patchTopic(general.getId(), patchSlug("general_2")))
                .isInstanceOf(CatalogConflictException.class);
        assertThat(topicRepository.findBySlug("general")).isPresent();
    }

    @Test
    void general_aRejectedProtectedChange_alsoDropsTheAllowedFieldsSentWithIt() {
        Topic general = fixtures.general();
        String label = general.getLabel();

        assertThatThrownBy(() -> service.patchTopic(
                        general.getId(), new TopicPatchRequest(null, "Renamed", null, null, false, null)))
                .isInstanceOf(CatalogConflictException.class);
        assertThat(topicRepository.findById(general.getId()).orElseThrow().getLabel()).isEqualTo(label);
    }

    @Test
    void general_patchThatRestatesItsProtectedValues_isAllowed() {
        Topic general = fixtures.general();

        TopicDto result = service.patchTopic(
                general.getId(), new TopicPatchRequest("general", null, null, null, true, Optional.empty()));

        assertThat(result.slug()).isEqualTo("general");
        assertThat(result.active()).isTrue();
        assertThat(result.topicGroupId()).isNull();
    }

    @Test
    void general_labelPromptAndOrder_stayEditable() {
        Topic general = fixtures.general();

        TopicDto result = service.patchTopic(
                general.getId(), new TopicPatchRequest(null, "Anything else", "Add anything.", 9999, null, null));

        assertThat(result).isEqualTo(new TopicDto(
                general.getId(), null, "general", "Anything else", "Add anything.", 9999, true));
    }
}
