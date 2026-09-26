package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

/** {@link ModerationService#reject(Long, String)} (UC-REJECT-TESTIMONIAL). */
@SpringBootTest
@Transactional
class ModerationServiceRejectTest {

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

    private Testimonial testimonial(String email, TestimonialStatus status) {
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
                .status(status)
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

    private Testimonial pendingTestimonial(String email) {
        return testimonial(email, TestimonialStatus.PENDING);
    }

    @Test
    void reject_withReason_sendsEmailContainingReasonAndSetsRejectedState() {
        Testimonial saved = pendingTestimonial("reject-with-reason@example.com");

        moderationService.reject(saved.getId(), "Please add more detail to the academics section.");

        verify(notificationMailer)
                .send(
                        eq("reject-with-reason@example.com"),
                        anyString(),
                        contains("Please add more detail to the academics section."));
        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
        assertThat(reloaded.getRejectedAt()).isEqualTo(FIXED_NOW);
        assertThat(reloaded.getReviewedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void reject_withoutReason_stillSendsEmailWithNoReasonVariant() {
        Testimonial saved = pendingTestimonial("reject-no-reason@example.com");

        moderationService.reject(saved.getId(), null);

        verify(notificationMailer).send(eq("reject-no-reason@example.com"), anyString(), anyString());
        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
        assertThat(reloaded.getRejectedAt()).isEqualTo(FIXED_NOW);
        assertThat(reloaded.getReviewedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void reject_blankReason_treatedSameAsNoReason() {
        Testimonial saved = pendingTestimonial("reject-blank-reason@example.com");

        moderationService.reject(saved.getId(), "   ");

        verify(notificationMailer).send(eq("reject-blank-reason@example.com"), anyString(), anyString());
        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_doesNotTouchIdentityScoreOrSectionModifiedFlags() {
        Testimonial saved = pendingTestimonial("reject-flags-unchanged@example.com");
        // Sanity: fixture starts with these all true/true/true.
        assertThat(saved.isIdentityModified()).isTrue();
        assertThat(saved.isScoreModified()).isTrue();
        assertThat(saved.getSections()).allMatch(TestimonialSection::isModified);

        moderationService.reject(saved.getId(), "some reason");

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isIdentityModified()).isTrue();
        assertThat(reloaded.isScoreModified()).isTrue();
        assertThat(reloaded.getSections()).allMatch(TestimonialSection::isModified);
    }

    @Test
    void reject_unknownId_throwsNotFoundException_andNeverSendsEmail() {
        assertThatThrownBy(() -> moderationService.reject(-1L, "reason"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(notificationMailer);
    }

    @Test
    void reject_alreadyApproved_throwsTestimonialNotPendingException() {
        Testimonial saved = testimonial("reject-already-approved@example.com", TestimonialStatus.APPROVED);

        assertThatThrownBy(() -> moderationService.reject(saved.getId(), "reason"))
                .isInstanceOf(TestimonialNotPendingException.class);
    }

    @Test
    void reject_alreadyRejected_throwsTestimonialNotPendingException() {
        Testimonial saved = pendingTestimonial("reject-already-rejected@example.com");
        moderationService.reject(saved.getId(), "first rejection");

        assertThatThrownBy(() -> moderationService.reject(saved.getId(), "second rejection"))
                .isInstanceOf(TestimonialNotPendingException.class);
    }
}
