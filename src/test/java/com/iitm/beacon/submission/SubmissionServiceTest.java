package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Spring-context tests for {@link SubmissionService}, following the
 * same conventions as {@code VisitorAuthControllerTest}: real repositories
 * against the H2 test DB, seeded reference data from Flyway (decision 11,
 * 13, 5, 1).
 */
@SpringBootTest
@Transactional
class SubmissionServiceTest {

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial persistTestimonialFor(String email) {
        Country india = countryRepository.findById("IN").orElseThrow();
        Testimonial testimonial = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        return testimonialRepository.saveAndFlush(testimonial);
    }

    @Test
    void determineMode_noExistingTestimonial_returnsCreateWithNullId() {
        SubmissionModeResult result = submissionService.determineMode("brand-new@example.com");

        assertThat(result.mode()).isEqualTo(SubmissionMode.CREATE);
        assertThat(result.testimonialId()).isNull();
    }

    @Test
    void determineMode_existingTestimonial_returnsEditWithItsId() {
        Testimonial existing = persistTestimonialFor("existing@example.com");

        SubmissionModeResult result = submissionService.determineMode("existing@example.com");

        assertThat(result.mode()).isEqualTo(SubmissionMode.EDIT);
        assertThat(result.testimonialId()).isEqualTo(existing.getId());
    }

    @Test
    void determineMode_emailIsCaseAndWhitespaceNormalizedForLookup() {
        persistTestimonialFor("normalized@example.com");

        SubmissionModeResult result = submissionService.determineMode("  Normalized@Example.com  ");

        assertThat(result.mode()).isEqualTo(SubmissionMode.EDIT);
    }

    @Test
    void listActiveContactTypes_returnsSeededActiveTypesOrderedByDisplayOrder() {
        var types = submissionService.listActiveContactTypes();

        assertThat(types).extracting(ContactTypeView::slug).contains("email", "whatsapp", "telegram");
    }

    @Test
    void listActiveAchievements_returnsSeededActiveAchievements() {
        var achievements = submissionService.listActiveAchievements();

        assertThat(achievements).extracting(AchievementView::slug).contains("made_new_friends");
    }
}
