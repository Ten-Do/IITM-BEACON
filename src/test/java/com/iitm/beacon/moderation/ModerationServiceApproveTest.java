package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.TestimonialNotPendingException;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** {@link ModerationService#approve(Long)} (UC-APPROVE-TESTIMONIAL). */
@SpringBootTest
@Transactional
class ModerationServiceApproveTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    private NotificationMailer notificationMailer;
    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        notificationMailer = mock(NotificationMailer.class);
        moderationService = new ModerationService(
                testimonialRepository, notificationMailer, new PhotoUrlResolver(), new MutableClock(FIXED_NOW));
    }

    private Testimonial pendingTestimonial(String email) {
        Testimonial t = Testimonial.builder()
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
                .createdAt(Instant.now())
                .identityModified(true)
                .scoreModified(true)
                .build();
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Great time overall.")
                .modified(true)
                .build();
        t.getSections().add(section);
        return testimonialRepository.save(t);
    }

    @Test
    void approve_pendingTestimonial_setsApprovedAndClearsAllFlags() {
        Testimonial saved = pendingTestimonial("approve-happy@example.com");

        moderationService.approve(saved.getId());

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(reloaded.getReviewedAt()).isEqualTo(FIXED_NOW);
        assertThat(reloaded.isIdentityModified()).isFalse();
        assertThat(reloaded.isScoreModified()).isFalse();
        assertThat(reloaded.getSections()).allMatch(s -> !s.isModified());
        assertThat(reloaded.getRejectedAt()).isNull();
    }

    @Test
    void approve_unknownId_throwsNotFoundException() {
        assertThatThrownBy(() -> moderationService.approve(-1L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(notificationMailer);
    }

    @Test
    void approve_alreadyApproved_throwsTestimonialNotPendingException() {
        Testimonial saved = pendingTestimonial("approve-already-approved@example.com");
        moderationService.approve(saved.getId());

        assertThatThrownBy(() -> moderationService.approve(saved.getId()))
                .isInstanceOf(TestimonialNotPendingException.class);
    }

    @Test
    void approve_alreadyRejected_throwsTestimonialNotPendingException() {
        Testimonial saved = pendingTestimonial("approve-already-rejected@example.com");
        moderationService.reject(saved.getId(), "not good enough");

        assertThatThrownBy(() -> moderationService.approve(saved.getId()))
                .isInstanceOf(TestimonialNotPendingException.class);
    }
}
