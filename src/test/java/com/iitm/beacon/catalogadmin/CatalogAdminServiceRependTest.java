package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * A hidden edit is never published unreviewed (decision 28,
 * UC-MANAGE-TOPIC-GROUPS / UC-MANAGE-TOPICS alt flows): when a topic turns
 * from invisible to visible — the topic or its group reactivated, or the
 * topic moved out of an inactive group — every {@code APPROVED} testimonial
 * with a {@code modified} section of it goes back to {@code PENDING}, with
 * {@code reviewedAt} and the identity/score flags left as they were. Nothing
 * else is touched: other statuses, unflagged sections, and catalog changes
 * that leave the topic's visibility as it was.
 */
@SpringBootTest
@Transactional
class CatalogAdminServiceRependTest {

    private static final Instant REVIEWED_AT = Instant.parse("2026-03-02T09:00:00Z");

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

    @Autowired
    private EntityManager entityManager;

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

    private static TopicPatchRequest topicActive(boolean active) {
        return new TopicPatchRequest(null, null, null, null, active, null);
    }

    private static TopicPatchRequest topicGroup(Optional<Long> topicGroupId) {
        return new TopicPatchRequest(null, null, null, null, null, topicGroupId);
    }

    private static TopicGroupPatchRequest groupActive(boolean active) {
        return new TopicGroupPatchRequest(null, null, active);
    }

    /**
     * A reviewed testimonial of {@code status} with one section per topic;
     * the sections of {@code modifiedTopics} carry {@code modified = true},
     * and both identity and score flags are set so a test can tell they
     * were left alone.
     */
    private Testimonial reviewed(TestimonialStatus status, List<Topic> topics, Set<Topic> modifiedTopics) {
        Testimonial t = fixtures.testimonial(status, topics);
        t.setReviewedAt(REVIEWED_AT);
        t.setIdentityModified(true);
        t.setScoreModified(true);
        t.getSections().forEach(s -> s.setModified(modifiedTopics.contains(s.getTopic())));
        return testimonialRepository.saveAndFlush(t);
    }

    private Testimonial approvedWithModified(Topic... topics) {
        return reviewed(TestimonialStatus.APPROVED, List.of(topics), Set.of(topics));
    }

    private Testimonial reload(Testimonial testimonial) {
        entityManager.flush();
        entityManager.clear();
        return testimonialRepository.findById(testimonial.getId()).orElseThrow();
    }

    private TestimonialStatus statusOf(Testimonial testimonial) {
        return reload(testimonial).getStatus();
    }

    // -- invisible -> visible: approved testimonials with a hidden edit go back to PENDING --

    @Test
    void reactivatingAStandaloneTopic_sendsTheApprovedTestimonialBackToPending_leavingReviewedAtAndFlags() {
        Topic hidden = fixtures.topic(null, "Hidden", 1, false);
        Testimonial approved = approvedWithModified(hidden);

        service.patchTopic(hidden.getId(), topicActive(true));

        Testimonial after = reload(approved);
        assertThat(after.getStatus()).isEqualTo(TestimonialStatus.PENDING);
        assertThat(after.getReviewedAt()).isEqualTo(REVIEWED_AT);
        assertThat(after.isIdentityModified()).isTrue();
        assertThat(after.isScoreModified()).isTrue();
        assertThat(after.getSections()).singleElement().satisfies(s -> assertThat(s.isModified()).isTrue());
    }

    @Test
    void reactivatingAGroup_sendsBackTestimonialsEditedUnderAnyOfItsActiveTopics_butNotUnderItsInactiveOne() {
        TopicGroup group = fixtures.group("Hidden group", 1, false);
        Topic first = fixtures.topic(group, "First", 1, true);
        Topic second = fixtures.topic(group, "Second", 2, true);
        Topic inactiveMember = fixtures.topic(group, "Inactive member", 3, false);
        Testimonial editedUnderFirst = approvedWithModified(first);
        Testimonial editedUnderBoth = approvedWithModified(first, second);
        Testimonial editedUnderInactiveMember = approvedWithModified(inactiveMember);

        service.patchTopicGroup(group.getId(), groupActive(true));

        assertThat(statusOf(editedUnderFirst)).isEqualTo(TestimonialStatus.PENDING);
        assertThat(statusOf(editedUnderBoth)).isEqualTo(TestimonialStatus.PENDING);
        assertThat(statusOf(editedUnderInactiveMember)).isEqualTo(TestimonialStatus.APPROVED);
    }

    @ParameterizedTest(name = "to standalone: {0}")
    @ValueSource(booleans = {false, true})
    void movingATopicOutOfAnInactiveGroup_sendsTheApprovedTestimonialBackToPending(boolean toStandalone) {
        TopicGroup inactiveGroup = fixtures.group("Hidden group", 1, false);
        Topic topic = fixtures.topic(inactiveGroup, "Moved", 1, true);
        Testimonial approved = approvedWithModified(topic);
        Optional<Long> target = toStandalone
                ? Optional.empty()
                : Optional.of(fixtures.group("Active group", 2, true).getId());

        service.patchTopic(topic.getId(), topicGroup(target));

        assertThat(statusOf(approved)).isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void reactivation_leavesPendingAndRejectedTestimonials_andApprovedOnesWithoutAFlaggedSectionOfTheTopic() {
        Topic hidden = fixtures.topic(null, "Hidden", 1, false);
        Topic otherHidden = fixtures.topic(null, "Other hidden", 2, false);
        Testimonial pending = reviewed(TestimonialStatus.PENDING, List.of(hidden), Set.of(hidden));
        Testimonial rejected = reviewed(TestimonialStatus.REJECTED, List.of(hidden), Set.of(hidden));
        Testimonial approvedUnflagged =
                reviewed(TestimonialStatus.APPROVED, List.of(hidden, otherHidden), Set.of(otherHidden));

        service.patchTopic(hidden.getId(), topicActive(true));

        assertThat(statusOf(pending)).isEqualTo(TestimonialStatus.PENDING);
        assertThat(statusOf(rejected)).isEqualTo(TestimonialStatus.REJECTED);
        assertThat(statusOf(approvedUnflagged)).isEqualTo(TestimonialStatus.APPROVED);
    }

    // -- no visibility change: nothing is touched --

    /** An inactive group holding an active and an inactive topic, plus a second inactive group. */
    private record HiddenCatalog(TopicGroup group, Topic activeMember, Topic inactiveMember, TopicGroup otherGroup) {
    }

    static Stream<Arguments> changesThatKeepTheTopicsHidden() {
        return Stream.of(
                Arguments.of("topic reactivated inside a still-inactive group", (Consumer<Change>) c ->
                        c.service().patchTopic(c.catalog().inactiveMember().getId(), topicActive(true))),
                Arguments.of("hidden topic relabelled", (Consumer<Change>) c -> c.service().patchTopic(
                        c.catalog().activeMember().getId(),
                        new TopicPatchRequest(null, "Relabelled", null, null, null, null))),
                Arguments.of("topic moved to another inactive group", (Consumer<Change>) c -> c.service()
                        .patchTopic(c.catalog().activeMember().getId(),
                                topicGroup(Optional.of(c.catalog().otherGroup().getId())))),
                Arguments.of("inactive group relabelled", (Consumer<Change>) c -> c.service().patchTopicGroup(
                        c.catalog().group().getId(), new TopicGroupPatchRequest("Relabelled", null, null))),
                Arguments.of("inactive group deactivated again", (Consumer<Change>) c ->
                        c.service().patchTopicGroup(c.catalog().group().getId(), groupActive(false))));
    }

    /** What a parameterized catalog change acts on. */
    private record Change(CatalogAdminService service, HiddenCatalog catalog) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changesThatKeepTheTopicsHidden")
    void aCatalogChangeThatKeepsTheTopicsHidden_leavesTheApprovedTestimonialApproved(
            String description, Consumer<Change> change) {
        TopicGroup group = fixtures.group("Hidden group", 1, false);
        HiddenCatalog catalog = new HiddenCatalog(
                group,
                fixtures.topic(group, "Active member", 1, true),
                fixtures.topic(group, "Inactive member", 2, false),
                fixtures.group("Other hidden group", 2, false));
        Testimonial approved = approvedWithModified(catalog.activeMember(), catalog.inactiveMember());

        change.accept(new Change(service, catalog));

        assertThat(statusOf(approved)).isEqualTo(TestimonialStatus.APPROVED);
    }

    @Test
    void aRefusedReactivation_leavesTheApprovedTestimonialApproved() {
        Topic hidden = fixtures.topic(null, "Hidden", 1, false);
        Testimonial approved = approvedWithModified(hidden);

        assertThatThrownBy(() -> service.patchTopic(
                        hidden.getId(), new TopicPatchRequest(Topic.GENERAL_SLUG, null, null, null, true, null)))
                .isInstanceOf(CatalogConflictException.class);

        assertThat(statusOf(approved)).isEqualTo(TestimonialStatus.APPROVED);
    }
}
