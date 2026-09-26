package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ModerationService#listPendingForView(org.springframework.data.domain.Pageable)} —
 * the admin queue-page view model: same underlying query as {@link
 * ModerationService#listPending}, but achievements resolved to slug+label
 * pairs and a {@code resubmitted} flag derived from {@code reviewedAt}.
 */
@SpringBootTest
@Transactional
class ModerationServiceListPendingForViewTest {

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        moderationService = new ModerationService(
                testimonialRepository,
                mock(NotificationMailer.class),
                new PhotoUrlResolver(),
                java.time.Clock.systemUTC());
    }

    private Country country(String code) {
        return countryRepository.findById(code).orElseThrow();
    }

    private Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    private ContactType contactType(String slug) {
        return contactTypeRepository.findBySlug(slug).orElseThrow();
    }

    private Achievement achievement(String slug) {
        return achievementRepository.findBySlug(slug).orElseThrow();
    }

    private Testimonial newTestimonial(String email, Instant createdAt) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(country("IN"))
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(createdAt)
                .build();
    }

    private void addSection(Testimonial t, String topicSlug, String answer, boolean modified) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic(topicSlug))
                .answerText(answer)
                .modified(modified)
                .build();
        t.getSections().add(section);
    }

    private void addContactMethod(Testimonial t, String typeSlug, String value, boolean isPublic) {
        ContactMethod cm = ContactMethod.builder()
                .testimonial(t)
                .contactType(contactType(typeSlug))
                .value(value)
                .isPublic(isPublic)
                .displayOrder(t.getContactMethods().size())
                .build();
        t.getContactMethods().add(cm);
    }

    private void addAchievement(Testimonial t, String achievementSlug) {
        t.getAchievements()
                .add(TestimonialAchievement.builder()
                        .testimonial(t)
                        .achievement(achievement(achievementSlug))
                        .build());
    }

    @Test
    void listPendingForView_emptyQueue_returnsEmptyContentAndZeroTotal() {
        PageResponse<ModerationQueueCardDto> result =
                moderationService.listPendingForView(PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void listPendingForView_newSubmission_mapsAchievementsAsSlugLabelAndResubmittedFalse() {
        Instant createdAt = Instant.parse("2026-02-01T10:00:00Z");
        Testimonial testimonial = newTestimonial("new-submission@example.com", createdAt);
        addSection(testimonial, "general", "Great time overall.", true);
        addContactMethod(testimonial, "whatsapp", "+1234567890", false);
        addAchievement(testimonial, "made_new_friends");
        testimonialRepository.save(testimonial);

        PageResponse<ModerationQueueCardDto> result =
                moderationService.listPendingForView(PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        ModerationQueueCardDto dto = result.content().get(0);
        assertThat(dto.email()).isEqualTo("new-submission@example.com");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
        assertThat(dto.resubmitted()).isFalse();
        assertThat(dto.achievements()).hasSize(1);
        assertThat(dto.achievements().get(0).slug()).isEqualTo("made_new_friends");
        assertThat(dto.achievements().get(0).label()).isNotBlank();
        assertThat(dto.sections()).hasSize(1);
        assertThat(dto.sections().get(0).topicSlug()).isEqualTo("general");
        assertThat(dto.contactMethods()).hasSize(1);
    }

    @Test
    void listPendingForView_previouslyReviewedResubmission_resubmittedTrue() {
        Testimonial testimonial = newTestimonial("resubmitted@example.com", Instant.now());
        addSection(testimonial, "general", "Updated answer.", true);
        // Simulates a testimonial that went through reject-then-resubmit (or
        // approve-then-edit) — status is back to PENDING but reviewedAt still
        // carries the timestamp of that earlier review.
        testimonial.setReviewedAt(Instant.parse("2026-01-15T09:00:00Z"));
        testimonialRepository.save(testimonial);

        PageResponse<ModerationQueueCardDto> result =
                moderationService.listPendingForView(PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).resubmitted()).isTrue();
    }

    @Test
    void listPendingForView_noAchievements_returnsEmptyAchievementsList() {
        Testimonial testimonial = newTestimonial("no-achievements@example.com", Instant.now());
        addSection(testimonial, "general", "Answer.", false);
        testimonialRepository.save(testimonial);

        PageResponse<ModerationQueueCardDto> result =
                moderationService.listPendingForView(PageRequest.of(0, 20));

        assertThat(result.content().get(0).achievements()).isEmpty();
    }
}
