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
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

class PhotoTagRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private PhotoTagRepository photoTagRepository;

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

    @Autowired
    private TestEntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Testimonial testimonial;
    private TestimonialSection section;
    private Photo photo;

    @BeforeEach
    void setUp() {
        Country india = countryRepository.findById("IN").orElseThrow();
        testimonial = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("phototag-test@example.com")
                .emailLookupHash(hashService.hash("phototag-test@example.com"))
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
        photo = photoRepository.saveAndFlush(
                Photo.builder().section(section).filePath("2026/01/photo1.jpg").displayOrder(1).build());
    }

    @Test
    void savedPhotoTag_roundTrips() {
        PhotoTag saved = photoTagRepository.saveAndFlush(
                PhotoTag.builder().photo(photo).tagText("sunset").build());

        var found = photoTagRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTagText()).isEqualTo("sunset");
    }

    @Test
    void duplicateTagTextOnSamePhoto_violatesUniqueConstraint() {
        photoTagRepository.saveAndFlush(PhotoTag.builder().photo(photo).tagText("beach").build());

        assertThatThrownBy(() ->
                        photoTagRepository.saveAndFlush(PhotoTag.builder().photo(photo).tagText("beach").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameTagText_onDifferentPhotos_isAllowed() {
        Photo otherPhoto = photoRepository.saveAndFlush(
                Photo.builder().section(section).filePath("2026/01/photo2.jpg").displayOrder(2).build());

        photoTagRepository.saveAndFlush(PhotoTag.builder().photo(photo).tagText("beach").build());
        PhotoTag saved = photoTagRepository.saveAndFlush(
                PhotoTag.builder().photo(otherPhoto).tagText("beach").build());

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void deletingTestimonial_cascadesThroughSectionPhotoAndPhotoTag() {
        PhotoTag tag = photoTagRepository.saveAndFlush(PhotoTag.builder().photo(photo).tagText("sunset").build());

        Long testimonialId = testimonial.getId();
        Long sectionId = section.getId();
        Long photoId = photo.getId();
        Long tagId = tag.getId();

        // Clear the persistence context and re-fetch: a freshly loaded
        // Testimonial's lazy "sections" collection reflects real DB state,
        // unlike the already-flushed in-memory instance from setUp() (whose
        // collection field is still the empty list the builder created) —
        // matching how a real delete-by-id service call would fetch first.
        entityManager.clear();
        Testimonial reloaded = testimonialRepository.findById(testimonialId).orElseThrow();

        testimonialRepository.delete(reloaded);
        testimonialRepository.flush();

        assertThat(testimonialRepository.findById(testimonialId)).isEmpty();
        assertThat(testimonialSectionRepository.findById(sectionId)).isEmpty();
        assertThat(photoRepository.findById(photoId)).isEmpty();
        assertThat(photoTagRepository.findById(tagId)).isEmpty();
    }
}
