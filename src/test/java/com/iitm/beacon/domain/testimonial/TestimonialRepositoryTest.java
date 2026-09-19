package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

class TestimonialRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Country india;

    @BeforeEach
    void setUp() {
        // "IN" is already seeded by V12 — look it up rather than inserting a
        // fresh row, which would now violate the PK.
        india = countryRepository.findById("IN").orElseThrow();
    }

    private Testimonial.TestimonialBuilder validTestimonialBuilder(String email) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void savedTestimonial_emailIsEncryptedAtRest_notPlaintextInRawColumn() {
        Testimonial saved = testimonialRepository.saveAndFlush(validTestimonialBuilder("visitor@example.com").build());

        Object rawEmail = entityManager
                .getEntityManager()
                .createNativeQuery("SELECT email FROM testimonial WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(rawEmail).isNotEqualTo("visitor@example.com");
    }

    @Test
    void findByEmailLookupHash_resolvesCorrectRow_forNormalizedEmail() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("someone@example.com").build());

        var found = testimonialRepository.findByEmailLookupHash(hashService.hash("Someone@Example.com "));

        assertThat(found).isPresent();
    }

    @Test
    void findByEmailLookupHash_unknownHash_returnsEmpty() {
        assertThat(testimonialRepository.findByEmailLookupHash("0".repeat(64))).isEmpty();
    }

    @Test
    void duplicateEmailLookupHash_violatesUniqueConstraint() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("first@example.com").build());

        Testimonial duplicate = validTestimonialBuilder("first@example.com").build();

        assertThatThrownBy(() -> testimonialRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recommendationScore_zeroAndTen_bothPersistSuccessfully() {
        Testimonial zero = testimonialRepository.saveAndFlush(
                validTestimonialBuilder("zero@example.com").recommendationScore(0).build());
        Testimonial ten = testimonialRepository.saveAndFlush(
                validTestimonialBuilder("ten@example.com").recommendationScore(10).build());

        assertThat(testimonialRepository.findById(zero.getId())).isPresent();
        assertThat(testimonialRepository.findById(ten.getId())).isPresent();
    }

    @Test
    void recommendationScore_belowZero_violatesCheckConstraint() {
        Testimonial invalid = validTestimonialBuilder("below@example.com").recommendationScore(-1).build();

        assertThatThrownBy(() -> testimonialRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recommendationScore_aboveTen_violatesCheckConstraint() {
        Testimonial invalid = validTestimonialBuilder("above@example.com").recommendationScore(11).build();

        assertThatThrownBy(() -> testimonialRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void status_defaultsToPending_whenNotExplicitlySet() {
        Testimonial saved = testimonialRepository.saveAndFlush(validTestimonialBuilder("pending@example.com").build());

        assertThat(saved.getStatus()).isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void booleanFlags_defaultToFalse_whenNotExplicitlySet() {
        Testimonial saved = testimonialRepository.saveAndFlush(validTestimonialBuilder("flags@example.com").build());

        assertThat(saved.isIdentityModified()).isFalse();
        assertThat(saved.isScoreModified()).isFalse();
    }
}
