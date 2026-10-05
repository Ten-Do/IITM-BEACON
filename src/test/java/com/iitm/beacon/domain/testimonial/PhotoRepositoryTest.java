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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

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

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Photo legacy(String filePath) {
        return photoRepository.saveAndFlush(
                Photo.builder().section(section).filePath(filePath).displayOrder(0).build());
    }

    private Photo converted(String name) {
        return photoRepository.saveAndFlush(Photo.builder()
                .section(section)
                .filePath(name + ".webp")
                .thumbnailPath(name + "-thumb.webp")
                .width(10)
                .height(10)
                .displayOrder(0)
                .build());
    }

    @Test
    void savedPhoto_roundTripsThumbnailAndSize() {
        Photo saved = converted("abc");
        entityManager.clear();

        Photo found = photoRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getThumbnailPath()).isEqualTo("abc-thumb.webp");
        assertThat(found.getWidth()).isEqualTo(10);
        assertThat(found.getHeight()).isEqualTo(10);
    }

    // -- legacy photos (no thumbnail yet), paged by id --

    @Test
    void findLegacy_returnsOnlyPhotosWithoutThumbnail_inIdOrder() {
        Photo first = legacy("a.jpeg");
        converted("done");
        Photo second = legacy("b.png");

        List<Photo> page = photoRepository.findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(0L, Limit.of(10));

        assertThat(page).extracting(Photo::getId).containsExactly(first.getId(), second.getId());
    }

    @Test
    void findLegacy_startsStrictlyAfterTheGivenId() {
        Photo first = legacy("a.jpeg");
        Photo second = legacy("b.jpeg");

        List<Photo> page =
                photoRepository.findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(first.getId(), Limit.of(10));

        assertThat(page).extracting(Photo::getId).containsExactly(second.getId());
    }

    @Test
    void findLegacy_returnsAtMostTheLimit() {
        Photo first = legacy("a.jpeg");
        legacy("b.jpeg");
        legacy("c.jpeg");

        List<Photo> page = photoRepository.findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(0L, Limit.of(1));

        assertThat(page).extracting(Photo::getId).containsExactly(first.getId());
    }

    @Test
    void findLegacy_afterTheLastOne_isEmpty() {
        Photo last = legacy("a.jpeg");
        converted("done");

        assertThat(photoRepository.findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(last.getId(), Limit.of(10)))
                .isEmpty();
    }

    // -- replacing a legacy photo's file, only if nothing changed it meanwhile --

    @Test
    void replaceLegacyFile_updatesPathsAndSize() {
        Photo photo = legacy("a.jpeg");

        int updated = photoRepository.replaceLegacyFile(photo.getId(), "a.jpeg", "n.webp", "n-thumb.webp", 40, 30);
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        Photo reloaded = photoRepository.findById(photo.getId()).orElseThrow();
        assertThat(reloaded.getFilePath()).isEqualTo("n.webp");
        assertThat(reloaded.getThumbnailPath()).isEqualTo("n-thumb.webp");
        assertThat(reloaded.getWidth()).isEqualTo(40);
        assertThat(reloaded.getHeight()).isEqualTo(30);
        assertThat(reloaded.getDisplayOrder()).isZero();
        assertThat(reloaded.getSection().getId()).isEqualTo(section.getId());
    }

    @Test
    void replaceLegacyFile_fileChangedMeanwhile_updatesNothing() {
        Photo photo = legacy("a.jpeg");

        int updated = photoRepository.replaceLegacyFile(photo.getId(), "other.jpeg", "n.webp", "n-thumb.webp", 4, 3);
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(photoRepository.findById(photo.getId()).orElseThrow().getFilePath()).isEqualTo("a.jpeg");
    }

    @Test
    void replaceLegacyFile_alreadyConverted_updatesNothing() {
        Photo photo = converted("done");

        int updated = photoRepository.replaceLegacyFile(photo.getId(), "done.webp", "n.webp", "n-thumb.webp", 4, 3);
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(photoRepository.findById(photo.getId()).orElseThrow().getThumbnailPath())
                .isEqualTo("done-thumb.webp");
    }

    @Test
    void replaceLegacyFile_photoDeletedMeanwhile_updatesNothing() {
        assertThat(photoRepository.replaceLegacyFile(-1L, "a.jpeg", "n.webp", "n-thumb.webp", 4, 3)).isZero();
    }

    @Test
    void section_isRequired_nullSectionViolatesNotNullConstraint() {
        Photo photo = Photo.builder().section(null).filePath("2026/01/photo2.jpg").displayOrder(1).build();

        assertThatThrownBy(() -> photoRepository.saveAndFlush(photo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
