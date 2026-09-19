package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class TestimonialSectionRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Testimonial testimonial;
    private Topic topic;

    @BeforeEach
    void setUp() {
        // "IN" (V12) and the "general" topic (V13) are already seeded — look
        // them up rather than inserting fresh rows, which would now violate
        // their unique constraints.
        Country india = countryRepository.findById("IN").orElseThrow();
        testimonial = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("section-test@example.com")
                .emailLookupHash(hashService.hash("section-test@example.com"))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        topic = topicRepository.findBySlug("general").orElseThrow();
    }

    @Test
    void savedSection_roundTrips() {
        TestimonialSection section = testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("It was a great experience overall.")
                .build());

        var found = testimonialSectionRepository.findById(section.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getAnswerText()).isEqualTo("It was a great experience overall.");
        assertThat(found.get().getTestimonial().getId()).isEqualTo(testimonial.getId());
        assertThat(found.get().getTopic().getId()).isEqualTo(topic.getId());
    }

    @Test
    void modified_defaultsToFalse_whenNotExplicitlySet() {
        TestimonialSection saved = testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("Some answer.")
                .build());

        assertThat(saved.isModified()).isFalse();
    }

    @Test
    void topic_isRequired_nullTopicViolatesNotNullConstraint() {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(null)
                .answerText("Missing topic.")
                .build();

        assertThatThrownBy(() -> testimonialSectionRepository.saveAndFlush(section))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
