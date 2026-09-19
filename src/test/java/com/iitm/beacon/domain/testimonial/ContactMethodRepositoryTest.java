package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

class ContactMethodRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private ContactMethodRepository contactMethodRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Testimonial testimonial;
    private ContactType emailType;

    @BeforeEach
    void setUp() {
        Country india = countryRepository.findById("IN").orElseThrow();
        testimonial = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("contact-method-test@example.com")
                .emailLookupHash(hashService.hash("contact-method-test@example.com"))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        emailType = contactTypeRepository.findBySlug("email").orElseThrow();
    }

    @Test
    void savedContactMethod_valueIsEncryptedAtRest_notPlaintextInRawColumn() {
        ContactMethod saved = contactMethodRepository.saveAndFlush(ContactMethod.builder()
                .testimonial(testimonial)
                .contactType(emailType)
                .value("public-contact@example.com")
                .displayOrder(1)
                .build());

        Object rawValue = entityManager
                .getEntityManager()
                .createNativeQuery("SELECT value FROM contact_method WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(rawValue).isNotEqualTo("public-contact@example.com");
    }

    @Test
    void savedContactMethod_roundTripsDecryptedValue() {
        ContactMethod saved = contactMethodRepository.saveAndFlush(ContactMethod.builder()
                .testimonial(testimonial)
                .contactType(emailType)
                .value("public-contact@example.com")
                .displayOrder(1)
                .build());

        var found = contactMethodRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getValue()).isEqualTo("public-contact@example.com");
    }

    @Test
    void isPublic_defaultsToFalse_whenNotExplicitlySet() {
        ContactMethod saved = contactMethodRepository.saveAndFlush(ContactMethod.builder()
                .testimonial(testimonial)
                .contactType(emailType)
                .value("private-contact@example.com")
                .displayOrder(1)
                .build());

        assertThat(saved.isPublic()).isFalse();
    }
}
