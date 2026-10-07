package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementId;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import com.iitm.beacon.domain.testimonial.PhotoTagRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CatalogAdminService}'s cascading deletes and their confirmation
 * previews (decision 28): a topic takes every section of it (with photos and
 * tags) along, a group all of its topics the same way, an achievement every
 * tick of it. Testimonials keep their status and their other content.
 *
 * <p>The test transaction never commits, so the photo files must not be
 * touched at all here — they go only after a commit, which {@code
 * CatalogAdminServicePhotoFilesTest} covers with real commits.
 */
@SpringBootTest
@Transactional
class CatalogAdminServiceDeleteTest {

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
    private PhotoRepository photoRepository;

    @Autowired
    private PhotoTagRepository photoTagRepository;

    @Autowired
    private EntityManager entityManager;

    private PhotoFileDeleter photoFileDeleter;
    private CatalogAdminService service;
    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        photoFileDeleter = mock(PhotoFileDeleter.class);
        service = new CatalogAdminService(
                topicGroupRepository,
                topicRepository,
                achievementRepository,
                testimonialRepository,
                testimonialSectionRepository,
                testimonialAchievementRepository,
                photoFileDeleter);
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Testimonial reload(Testimonial t) {
        return testimonialRepository.findById(t.getId()).orElseThrow();
    }

    private static List<Long> photoIds(Testimonial t) {
        return t.getSections().stream().flatMap(s -> s.getPhotos().stream()).map(Photo::getId).toList();
    }

    private static List<Long> topicIdsOf(Testimonial t) {
        return t.getSections().stream().map(s -> s.getTopic().getId()).toList();
    }

    // -- topic --

    @Test
    void deleteTopic_removesItsSectionsPhotosAndTags_keepsEverythingElse() {
        Topic doomed = fixtures.topic(null, "Doomed", 1, true);
        Topic kept = fixtures.topic(null, "Kept", 1, true);
        Testimonial approved = fixtures.testimonial(
                TestimonialStatus.APPROVED, List.of(doomed, kept),
                List.of(CatalogFixtures.photo("p1", "beach", "sunset"), CatalogFixtures.photo("p2")), List.of());
        Testimonial pending = fixtures.testimonial(TestimonialStatus.PENDING, List.of(kept));
        List<Long> doomedPhotoIds = photoIds(approved);
        long tagsBefore = photoTagRepository.count();
        flushAndClear();

        service.deleteTopic(doomed.getId());
        flushAndClear();

        assertThat(topicRepository.findById(doomed.getId())).isEmpty();
        assertThat(photoRepository.findAllById(doomedPhotoIds)).isEmpty();
        assertThat(photoTagRepository.count()).isEqualTo(tagsBefore - 2);
        Testimonial reloaded = reload(approved);
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(topicIdsOf(reloaded)).containsExactly(kept.getId());
        assertThat(reloaded.getSections().get(0).getAnswerText()).isEqualTo("About Kept");
        assertThat(topicIdsOf(reload(pending))).containsExactly(kept.getId());
        assertThat(reload(pending).getStatus()).isEqualTo(TestimonialStatus.PENDING);
        verifyNoInteractions(photoFileDeleter);
    }

    @Test
    void deleteTopic_canLeaveATestimonialWithNoSectionAtAll_statusUnchanged() {
        Topic doomed = fixtures.topic(null, "Doomed", 1, true);
        Testimonial only = fixtures.testimonial(TestimonialStatus.REJECTED, List.of(doomed));
        flushAndClear();

        service.deleteTopic(doomed.getId());
        flushAndClear();

        Testimonial reloaded = reload(only);
        assertThat(reloaded.getSections()).isEmpty();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void deleteTopic_withNoSections_deletesJustTheTopic() {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic unused = fixtures.topic(group, "Unused", 1, false);
        long sectionsBefore = testimonialSectionRepository.count();

        service.deleteTopic(unused.getId());
        flushAndClear();

        assertThat(topicRepository.findById(unused.getId())).isEmpty();
        assertThat(topicGroupRepository.findById(group.getId())).isPresent();
        assertThat(testimonialSectionRepository.count()).isEqualTo(sectionsBefore);
    }

    @Test
    void deleteTopic_general_throwsConflictAndDeletesNothing() {
        Topic general = fixtures.general();
        Testimonial t = fixtures.testimonial(TestimonialStatus.APPROVED, List.of(general));
        flushAndClear();

        assertThatThrownBy(() -> service.deleteTopic(general.getId())).isInstanceOf(CatalogConflictException.class);
        flushAndClear();

        assertThat(topicRepository.findById(general.getId())).isPresent();
        assertThat(reload(t).getSections()).hasSize(1);
    }

    @Test
    void deleteTopic_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.deleteTopic(987_654L)).isInstanceOf(NotFoundException.class);
    }

    // -- topic group --

    @Test
    void deleteTopicGroup_removesTheGroupAllItsTopicsAndTheirSections() {
        TopicGroup doomed = fixtures.group("Doomed", 1, true);
        Topic t1 = fixtures.topic(doomed, "T1", 1, true);
        Topic t2 = fixtures.topic(doomed, "T2", 2, false);
        Topic t3 = fixtures.topic(doomed, "T3", 3, true);
        TopicGroup otherGroup = fixtures.group("Other", 2, true);
        Topic elsewhere = fixtures.topic(otherGroup, "Elsewhere", 1, true);
        Testimonial a = fixtures.testimonial(
                TestimonialStatus.APPROVED, List.of(t1, t2, elsewhere),
                List.of(CatalogFixtures.photo("g1", "tag"), CatalogFixtures.photo("g2")), List.of());
        Testimonial b = fixtures.testimonial(
                TestimonialStatus.PENDING, List.of(t3, fixtures.general()),
                List.of(CatalogFixtures.photo("g3")), List.of());
        Testimonial untouched = fixtures.testimonial(
                TestimonialStatus.APPROVED, List.of(elsewhere), List.of(CatalogFixtures.photo("keep")), List.of());
        List<Long> doomedPhotos = List.of(photoIds(a).get(0), photoIds(a).get(1), photoIds(b).get(0));
        List<Long> keptPhotos = photoIds(untouched);
        flushAndClear();

        service.deleteTopicGroup(doomed.getId());
        flushAndClear();

        assertThat(topicGroupRepository.findById(doomed.getId())).isEmpty();
        assertThat(topicRepository.findAllById(List.of(t1.getId(), t2.getId(), t3.getId()))).isEmpty();
        assertThat(photoRepository.findAllById(doomedPhotos)).isEmpty();
        assertThat(photoRepository.findAllById(keptPhotos)).hasSize(1);
        assertThat(topicIdsOf(reload(a))).containsExactly(elsewhere.getId());
        assertThat(topicIdsOf(reload(b))).containsExactly(fixtures.general().getId());
        assertThat(topicIdsOf(reload(untouched))).containsExactly(elsewhere.getId());
        assertThat(reload(a).getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(reload(b).getStatus()).isEqualTo(TestimonialStatus.PENDING);
        assertThat(topicGroupRepository.findById(otherGroup.getId())).isPresent();
        verifyNoInteractions(photoFileDeleter);
    }

    @Test
    void deleteTopicGroup_withoutTopics_deletesJustTheGroup() {
        TopicGroup empty = fixtures.group("Empty", 1, false);

        service.deleteTopicGroup(empty.getId());
        flushAndClear();

        assertThat(topicGroupRepository.findById(empty.getId())).isEmpty();
    }

    @Test
    void deleteTopicGroup_thatSomehowHoldsGeneral_throwsConflictAndDeletesNothing() {
        // Not reachable through the catalog (general can't be moved into a
        // group), but a group delete must never take general with it.
        TopicGroup group = fixtures.group("Holder", 1, true);
        Topic general = fixtures.general();
        general.setTopicGroup(group);
        topicRepository.saveAndFlush(general);

        assertThatThrownBy(() -> service.deleteTopicGroup(group.getId()))
                .isInstanceOf(CatalogConflictException.class);
        assertThat(topicRepository.findBySlug("general")).isPresent();
        assertThat(topicGroupRepository.findById(group.getId())).isPresent();
    }

    @Test
    void deleteTopicGroup_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.deleteTopicGroup(987_654L)).isInstanceOf(NotFoundException.class);
    }

    // -- achievement --

    @Test
    void deleteAchievement_removesEveryTickOfIt_keepsOtherTicksSectionsAndStatus() {
        Achievement doomed = fixtures.achievement("Doomed", 1, true);
        Achievement kept = fixtures.achievement("Kept", 2, true);
        Testimonial a = fixtures.testimonial(
                TestimonialStatus.APPROVED, List.of(fixtures.general()), List.of(), List.of(doomed, kept));
        Testimonial b = fixtures.testimonial(
                TestimonialStatus.PENDING, List.of(fixtures.general()), List.of(), List.of(doomed));
        flushAndClear();

        service.deleteAchievement(doomed.getId());
        flushAndClear();

        assertThat(achievementRepository.findById(doomed.getId())).isEmpty();
        assertThat(testimonialAchievementRepository.countByAchievementId(doomed.getId())).isZero();
        assertThat(testimonialAchievementRepository.existsById(
                new TestimonialAchievementId(a.getId(), kept.getId()))).isTrue();
        assertThat(reload(a).getAchievements()).hasSize(1);
        assertThat(reload(b).getAchievements()).isEmpty();
        assertThat(reload(a).getSections()).hasSize(1);
        assertThat(reload(a).getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(reload(b).getStatus()).isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void deleteAchievement_neverTicked_deletesJustIt() {
        Achievement unused = fixtures.achievement("Unused", 1, false);

        service.deleteAchievement(unused.getId());
        flushAndClear();

        assertThat(achievementRepository.findById(unused.getId())).isEmpty();
    }

    @Test
    void deleteAchievement_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.deleteAchievement(987_654L)).isInstanceOf(NotFoundException.class);
    }

    // -- previews --

    @Test
    void previewTopicDelete_countsEachTestimonialWithASectionOnce_ofAnyStatus() {
        Topic topic = fixtures.topic(null, "Counted", 1, true);
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(topic));
        fixtures.testimonial(TestimonialStatus.PENDING, List.of(topic, fixtures.general()));
        fixtures.testimonial(TestimonialStatus.REJECTED, List.of(topic));
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(fixtures.general()));

        assertThat(service.previewTopicDelete(topic.getId())).isEqualTo(new DeletePreview("Counted", 3, 1));
    }

    @Test
    void previewTopicDelete_unusedTopic_countsZero() {
        Topic topic = fixtures.topic(null, "Unused", 1, true);

        assertThat(service.previewTopicDelete(topic.getId())).isEqualTo(new DeletePreview("Unused", 0, 1));
    }

    @Test
    void previewTopicDelete_general_throwsConflict() {
        assertThatThrownBy(() -> service.previewTopicDelete(fixtures.general().getId()))
                .isInstanceOf(CatalogConflictException.class);
    }

    @Test
    void previewTopicGroupDelete_countsDistinctTestimonialsAcrossAllItsTopics() {
        TopicGroup group = fixtures.group("Grouped", 1, true);
        Topic t1 = fixtures.topic(group, "T1", 1, true);
        Topic t2 = fixtures.topic(group, "T2", 2, true);
        fixtures.topic(group, "T3 unused", 3, true);
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(t1, t2));
        fixtures.testimonial(TestimonialStatus.PENDING, List.of(t2));
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(fixtures.general()));

        assertThat(service.previewTopicGroupDelete(group.getId())).isEqualTo(new DeletePreview("Grouped", 2, 3));
    }

    @Test
    void previewTopicGroupDelete_groupWithoutTopics_countsZero() {
        TopicGroup group = fixtures.group("Empty", 1, true);

        assertThat(service.previewTopicGroupDelete(group.getId())).isEqualTo(new DeletePreview("Empty", 0, 0));
    }

    @Test
    void previewAchievementDelete_countsTheTestimonialsThatTickedIt() {
        Achievement achievement = fixtures.achievement("Ticked", 1, true);
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(fixtures.general()), List.of(), List.of(achievement));
        fixtures.testimonial(TestimonialStatus.REJECTED, List.of(fixtures.general()), List.of(), List.of(achievement));

        assertThat(service.previewAchievementDelete(achievement.getId())).isEqualTo(new DeletePreview("Ticked", 2, 0));
    }

    @Test
    void previews_unknownId_throwNotFound() {
        assertThatThrownBy(() -> service.previewTopicDelete(987_654L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.previewTopicGroupDelete(987_654L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.previewAchievementDelete(987_654L)).isInstanceOf(NotFoundException.class);
    }
}
