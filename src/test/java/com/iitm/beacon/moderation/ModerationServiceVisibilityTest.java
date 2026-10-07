package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cascading visibility in moderation (decision 28, UC-VIEW-PENDING-QUEUE,
 * UC-APPROVE-TESTIMONIAL): both the REST queue ({@code listPending}) and the
 * queue page ({@code listPendingForView}) leave out sections of invisible
 * topics — with their photos and diff flags — and ticks of inactive
 * achievements, and show them again once reactivated; approving clears
 * {@code modified} only on the sections the admin could see.
 */
@SpringBootTest
@Transactional
class ModerationServiceVisibilityTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 100);

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EntityManager entityManager;

    private ModerationService moderationService;

    private CatalogVisibilityFixture catalog;

    @BeforeEach
    void setUp() {
        moderationService = new ModerationService(
                testimonialRepository, mock(NotificationMailer.class), new PhotoUrlResolver(), Clock.systemUTC());
        catalog = CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
    }

    private Testimonial pending(String email) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private static void addSection(Testimonial t, Topic topic, String answer, boolean modified, String photoFile) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic)
                .answerText(answer)
                .modified(modified)
                .build();
        section.getPhotos().add(Photo.builder().section(section).filePath(photoFile).displayOrder(0).build());
        t.getSections().add(section);
    }

    private static void addAchievement(Testimonial t, Achievement achievement) {
        t.getAchievements().add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());
    }

    private Testimonial mixedPending(String email) {
        Testimonial t = pending(email);
        addSection(t, catalog.inactiveTopic(), "Inactive words.", true, "inactive.webp");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.", true, "hidden-group.webp");
        addSection(t, catalog.visibleTopic(), "Visible words.", false, "visible.webp");
        addAchievement(t, catalog.visibleAchievement());
        addAchievement(t, catalog.inactiveAchievement());
        return testimonialRepository.saveAndFlush(t);
    }

    private ModerationTestimonialDetailDto detail(String email) {
        return moderationService.listPending(FIRST_PAGE).content().stream()
                .filter(d -> d.email().equals(email))
                .findFirst()
                .orElseThrow();
    }

    private ModerationQueueCardDto queueCard(String email) {
        return moderationService.listPendingForView(FIRST_PAGE).content().stream()
                .filter(c -> c.email().equals(email))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void listPending_leavesOutHiddenSectionsWithTheirPhotosAndFlags_andHiddenAchievements() {
        mixedPending("mod-vis-rest@example.com");

        ModerationTestimonialDetailDto detail = detail("mod-vis-rest@example.com");

        assertThat(detail.sections())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.topicSlug()).isEqualTo(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG);
                    assertThat(s.updated()).isFalse();
                    assertThat(s.photos())
                            .extracting(ModerationPhotoRefDto::url)
                            .containsExactly("/uploads/visible.webp");
                });
        assertThat(detail.achievements()).containsExactly(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG);
    }

    @Test
    void listPendingForView_leavesOutHiddenSectionsWithTheirPhotosAndFlags_andHiddenAchievements() {
        mixedPending("mod-vis-page@example.com");

        ModerationQueueCardDto card = queueCard("mod-vis-page@example.com");

        assertThat(card.sections())
                .extracting(ModerationSectionViewDto::answer)
                .containsExactly("Visible words.");
        assertThat(card.sections()).noneMatch(ModerationSectionViewDto::updated);
        assertThat(card.achievements())
                .extracting(ModerationAchievementViewDto::slug)
                .containsExactly(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG);
    }

    @Test
    void listPending_testimonialWithEverySectionHidden_isStillQueued_withoutSections() {
        Testimonial t = pending("mod-vis-all-hidden@example.com");
        addSection(t, catalog.inactiveTopic(), "Inactive words.", true, "inactive.webp");
        addAchievement(t, catalog.inactiveAchievement());
        testimonialRepository.saveAndFlush(t);

        assertThat(detail("mod-vis-all-hidden@example.com").sections()).isEmpty();
        assertThat(detail("mod-vis-all-hidden@example.com").achievements()).isEmpty();
        assertThat(queueCard("mod-vis-all-hidden@example.com").sections()).isEmpty();
        assertThat(queueCard("mod-vis-all-hidden@example.com").achievements()).isEmpty();
    }

    @Test
    void listPending_afterReactivation_showsTheHiddenSectionsFlagsAndAchievementsAgainUnchanged() {
        mixedPending("mod-vis-reactivate@example.com");
        catalog.reactivateAll(topicGroupRepository, topicRepository, achievementRepository);
        entityManager.flush();
        entityManager.clear();

        ModerationTestimonialDetailDto detail = detail("mod-vis-reactivate@example.com");
        ModerationQueueCardDto card = queueCard("mod-vis-reactivate@example.com");

        assertThat(detail.sections())
                .extracting(ModerationSectionViewDto::answer, ModerationSectionViewDto::updated)
                .containsExactlyInAnyOrder(
                        tuple("Inactive words.", true),
                        tuple("Hidden group words.", true),
                        tuple("Visible words.", false));
        assertThat(detail.achievements())
                .containsExactlyInAnyOrder(
                        CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG,
                        CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);
        assertThat(card.sections()).hasSize(3);
        assertThat(card.achievements()).hasSize(2);
    }

    @Test
    void approve_clearsModifiedOnlyOnVisibleSections_hiddenSectionsKeepTheirFlag() {
        Testimonial t = pending("mod-vis-approve@example.com");
        t.setIdentityModified(true);
        t.setScoreModified(true);
        addSection(t, catalog.inactiveTopic(), "Inactive words.", true, "inactive.webp");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.", true, "hidden-group.webp");
        addSection(t, catalog.visibleTopic(), "Visible words.", true, "visible.webp");
        Long id = testimonialRepository.saveAndFlush(t).getId();

        moderationService.approve(id);
        entityManager.flush();
        entityManager.clear();

        Testimonial approved = testimonialRepository.findById(id).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(approved.isIdentityModified()).isFalse();
        assertThat(approved.isScoreModified()).isFalse();
        assertThat(approved.getSections())
                .extracting(s -> s.getTopic().getSlug(), TestimonialSection::isModified)
                .containsExactlyInAnyOrder(
                        tuple(CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, true),
                        tuple(CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG, true),
                        tuple(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG, false));
    }
}
