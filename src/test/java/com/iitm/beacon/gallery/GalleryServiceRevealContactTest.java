package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.NotFoundException;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class GalleryServiceRevealContactTest {

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial.TestimonialBuilder testimonial(String email) {
        Country india = countryRepository.findById("IN").orElseThrow();
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    private TestimonialSection generalSection(Testimonial t) {
        return TestimonialSection.builder()
                .testimonial(t)
                .topic(topic("general"))
                .answerText("Text.")
                .modified(false)
                .build();
    }

    private ContactType emailType() {
        return contactTypeRepository.findBySlug("email").orElseThrow();
    }

    private ContactType whatsappType() {
        return contactTypeRepository.findBySlug("whatsapp").orElseThrow();
    }

    @Test
    void revealContact_approvedWithPublicAndPrivateEntries_returnsOnlyPublicOnes() {
        Testimonial t = testimonial("reveal-happy@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(generalSection(t));
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(emailType())
                        .value("public@example.com")
                        .isPublic(true)
                        .displayOrder(0)
                        .build());
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(whatsappType())
                        .value("+10000000000")
                        .isPublic(false)
                        .displayOrder(1)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        List<ContactMethodViewDto> result = galleryService.revealContact(saved.getId());

        assertThat(result).containsExactly(new ContactMethodViewDto("email", "public@example.com"));
    }

    @Test
    void revealContact_multiplePublicEntries_returnsAllOfThem() {
        Testimonial t = testimonial("reveal-multi-public@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(generalSection(t));
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(emailType())
                        .value("public@example.com")
                        .isPublic(true)
                        .displayOrder(0)
                        .build());
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(whatsappType())
                        .value("+10000000000")
                        .isPublic(true)
                        .displayOrder(1)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        List<ContactMethodViewDto> result = galleryService.revealContact(saved.getId());

        assertThat(result).hasSize(2);
    }

    @Test
    void revealContact_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> galleryService.revealContact(999_999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void revealContact_sameNotFoundMessage_acrossUnknownPendingAndZeroPublicContacts() {
        Testimonial pending = testimonial("reveal-pending@example.com")
                .status(TestimonialStatus.PENDING)
                .build();
        pending.getSections().add(generalSection(pending));
        pending.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(pending)
                        .contactType(emailType())
                        .value("pending@example.com")
                        .isPublic(true)
                        .displayOrder(0)
                        .build());
        Testimonial savedPending = testimonialRepository.saveAndFlush(pending);

        Testimonial approvedNoPublicContacts = testimonial("reveal-approved-no-public@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        approvedNoPublicContacts.getSections().add(generalSection(approvedNoPublicContacts));
        approvedNoPublicContacts
                .getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(approvedNoPublicContacts)
                        .contactType(emailType())
                        .value("private@example.com")
                        .isPublic(false)
                        .displayOrder(0)
                        .build());
        Testimonial savedApprovedNoPublic = testimonialRepository.saveAndFlush(approvedNoPublicContacts);

        String unknownMessage = catchMessage(() -> galleryService.revealContact(999_999L));
        String pendingMessage = catchMessage(() -> galleryService.revealContact(savedPending.getId()));
        String zeroPublicMessage = catchMessage(() -> galleryService.revealContact(savedApprovedNoPublic.getId()));

        assertThat(pendingMessage).isEqualTo(unknownMessage);
        assertThat(zeroPublicMessage).isEqualTo(unknownMessage);
    }

    @Test
    void revealContact_approvedWithZeroContactMethodsAtAll_throwsNotFound() {
        Testimonial t = testimonial("reveal-zero-contacts@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(generalSection(t));
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        assertThatThrownBy(() -> galleryService.revealContact(saved.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void revealContact_rejectedTestimonial_throwsNotFoundWithSameMessageAsUnknownId() {
        Testimonial rejected = testimonial("reveal-rejected@example.com")
                .status(TestimonialStatus.REJECTED)
                .build();
        rejected.getSections().add(generalSection(rejected));
        rejected.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(rejected)
                        .contactType(emailType())
                        .value("rejected@example.com")
                        .isPublic(true)
                        .displayOrder(0)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(rejected);

        String unknownMessage = catchMessage(() -> galleryService.revealContact(999_999L));
        String rejectedMessage = catchMessage(() -> galleryService.revealContact(saved.getId()));

        assertThat(rejectedMessage).isEqualTo(unknownMessage);
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
        } catch (NotFoundException ex) {
            return ex.getMessage();
        }
        throw new AssertionError("Expected NotFoundException to be thrown");
    }
}
