package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.NotificationNotSentException;
import com.iitm.beacon.common.error.TestimonialNotPendingException;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.LogCapture;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.annotation.Transactional;

/** {@link ModerationService#reject(Long, String)} (UC-REJECT-TESTIMONIAL). */
@SpringBootTest
@Transactional
class ModerationServiceRejectTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-01T10:00:00Z");
    private static final String NOT_SENT_MESSAGE =
            "The email to the submitter couldn't be sent, so the testimonial was not rejected. Try again later.";

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    private NotificationMailer notificationMailer;
    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        notificationMailer = mock(NotificationMailer.class);
        moderationService = new ModerationService(
                testimonialRepository,
                notificationMailer,
                new PhotoUrlResolver(),
                mock(PhotoFileDeleter.class),
                new MutableClock(FIXED_NOW));
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

    // -- what the email says --

    /** The notification's subject and body are logged as they are in dev, so they must hold nothing personal. */
    @Test
    void reject_emailHoldsNeitherTheSubmittersAddressNorAnyOfTheirContacts() {
        Testimonial saved = pendingTestimonial("jane.doe@example.com");
        addContact(saved, "whatsapp", "+1234567890");
        addContact(saved, "telegram", "@janedoe");

        moderationService.reject(saved.getId(), "Please expand the academics section.");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationMailer).send(eq("jane.doe@example.com"), subject.capture(), body.capture());
        assertThat(subject.getValue() + " " + body.getValue())
                .contains("Please expand the academics section.")
                .doesNotContain("jane.doe", "example.com", "+1234567890", "@janedoe", "David", "Jones", "GE26Z001");
    }

    // -- the email can't be sent --

    @Test
    void reject_whenTheEmailCannotBeSent_throwsNotificationNotSent_andLeavesTheTestimonialPending() {
        Testimonial saved = pendingTestimonial("jane@example.com");
        Instant earlierReview = Instant.parse("2026-01-15T08:00:00Z");
        saved.setReviewedAt(earlierReview);
        mailFailsWith("550 Recipient address rejected: jane@example.com");

        assertThatThrownBy(() -> moderationService.reject(saved.getId(), "Please add more detail."))
                .isInstanceOf(NotificationNotSentException.class)
                .hasMessage(NOT_SENT_MESSAGE)
                .hasNoCause();

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.PENDING);
        assertThat(reloaded.getRejectedAt()).isNull();
        assertThat(reloaded.getReviewedAt()).isEqualTo(earlierReview);
    }

    @Test
    void reject_whenTheEmailCannotBeSent_withoutAReason_isNotRejectedEither() {
        Testimonial saved = pendingTestimonial("jane-no-reason@example.com");
        mailFailsWith("550 Recipient address rejected: jane-no-reason@example.com");

        assertThatThrownBy(() -> moderationService.reject(saved.getId(), null))
                .isInstanceOf(NotificationNotSentException.class);

        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void reject_whenTheEmailCannotBeSent_warnsWithoutTheAddressOrTheMailServersMessage() {
        Testimonial saved = pendingTestimonial("jane@example.com");
        mailFailsWith("550 Recipient address rejected: jane@example.com");

        assertThatThrownBy(() -> moderationService.reject(saved.getId(), "reason"))
                .isInstanceOf(NotificationNotSentException.class);

        assertThat(logs.warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("Testimonial " + saved.getId() + " was not rejected")
                .contains("MailSendException"));
        assertThat(logs.all()).noneMatch(line -> line.contains("jane"))
                .noneMatch(line -> line.contains("550"))
                .noneMatch(line -> line.contains("Recipient address rejected"));
        assertThat(logs.errors()).isEmpty();
    }

    @Test
    void reject_afterAFailedEmail_succeedsOnceTheEmailCanBeSent() {
        Testimonial saved = pendingTestimonial("jane-retry@example.com");
        mailFailsWith("421 Service not available");
        assertThatThrownBy(() -> moderationService.reject(saved.getId(), "reason"))
                .isInstanceOf(NotificationNotSentException.class);

        NotificationMailer working = mock(NotificationMailer.class);
        moderationService = new ModerationService(
                testimonialRepository, working, new PhotoUrlResolver(), mock(PhotoFileDeleter.class),
                new MutableClock(FIXED_NOW));
        moderationService.reject(saved.getId(), "reason");

        verify(working).send(eq("jane-retry@example.com"), anyString(), contains("reason"));
        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
        assertThat(reloaded.getRejectedAt()).isEqualTo(FIXED_NOW);
    }

    private void mailFailsWith(String serverMessage) {
        doThrow(new MailSendException(serverMessage)).when(notificationMailer).send(any(), any(), any());
    }

    private void addContact(Testimonial testimonial, String typeSlug, String value) {
        testimonial.getContactMethods().add(ContactMethod.builder()
                .testimonial(testimonial)
                .contactType(contactTypeRepository.findBySlug(typeSlug).orElseThrow())
                .value(value)
                .isPublic(false)
                .displayOrder(testimonial.getContactMethods().size())
                .build());
        testimonialRepository.saveAndFlush(testimonial);
    }
}
