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

class PhotoRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private PhotoRepository photoRepository;

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

    private TestimonialSection section;

    @BeforeEach
    void setUp() {
        Country india = countryRepository.findById("IN").orElseThrow();
        Testimonial testimonial = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("photo-test@example.com")
                .emailLookupHash(hashService.hash("photo-test@example.com"))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        Topic topic = topicRepository.findBySlug("general").orElseThrow();
        section = testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("Some answer.")
                .build());
    }

    @Test
    void savedPhoto_roundTrips() {
        Photo saved = photoRepository.saveAndFlush(
                Photo.builder().section(section).filePath("2026/01/photo1.jpg").displayOrder(1).build());

        var found = photoRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getFilePath()).isEqualTo("2026/01/photo1.jpg");
        assertThat(found.get().getSection().getId()).isEqualTo(section.getId());
    }

    @Test
    void section_isRequired_nullSectionViolatesNotNullConstraint() {
        Photo photo = Photo.builder().section(null).filePath("2026/01/photo2.jpg").displayOrder(1).build();

        assertThatThrownBy(() -> photoRepository.saveAndFlush(photo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
