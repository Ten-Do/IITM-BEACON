package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@link SubmissionService} does with photo FILES on create/edit: every
 * upload becomes a full-size + thumbnail WebP pair whose paths and size land
 * on the {@link Photo} row, and a photo removed by an edit (or with its whole
 * section) loses both of its files. Photos are stored in this test's own
 * uploads root so the files can be inspected; rows are re-read from the
 * database (flush + clear), not from the persistence context.
 */
@SpringBootTest
@Transactional
class SubmissionServicePhotoFilesTest {

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private Clock clock;

    @Autowired
    private Validator validator;

    @Autowired
    private EntityManager entityManager;

    @TempDir
    Path uploadsRoot;

    private SubmissionService submissionService;

    @BeforeEach
    void serviceStoringPhotosInTempRoot() {
        PhotoStorageProperties properties = TestPhotoStorage.properties(uploadsRoot);
        submissionService = new SubmissionService(
                testimonialRepository,
                topicRepository,
                achievementRepository,
                contactTypeRepository,
                countryRepository,
                emailLookupHashService,
                new PhotoStorageService(
                        properties,
                        new PhotoUrlResolver(),
                        new PhotoImageProcessor(properties),
                        new PhotoFileDeleter(properties)),
                properties,
                clock,
                topicGroupRepository,
                validator);
    }

    private static MockMultipartFile landscapePng(String part) {
        return new MockMultipartFile(
                part, part + ".png", "image/png", TestImages.png(TestImages.solid(3000, 2000, TestImages.BLUE)));
    }

    private static TestimonialSubmissionRequest request(SectionInput... sections) {
        return new TestimonialSubmissionRequest(
                "David", "Jones", "GE26Z001", 2024, "IN", 8, List.of(sections), List.of(), List.of(), true);
    }

    private static SectionInput section(String topic, String answer, PhotoInput... photos) {
        return new SectionInput(topic, answer, List.of(photos));
    }

    private static PhotoInput photo(String fileRef) {
        return new PhotoInput(fileRef, List.of());
    }

    private Testimonial reload(String email) {
        entityManager.flush();
        entityManager.clear();
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).orElseThrow();
    }

    private static TestimonialSection sectionFor(Testimonial testimonial, String topic) {
        return testimonial.getSections().stream()
                .filter(s -> topic.equals(s.getTopic().getSlug()))
                .findFirst()
                .orElseThrow();
    }

    private List<String> uploadedFiles() throws IOException {
        try (Stream<Path> files = Files.list(uploadsRoot)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void assertStoredAsWebpPair(Photo photo) {
        assertThat(photo.getFilePath()).endsWith(".webp");
        assertThat(photo.getThumbnailPath()).isEqualTo(photo.getFilePath().replace(".webp", "-thumb.webp"));
        assertThat(photo.getWidth()).isEqualTo(2560);
        assertThat(photo.getHeight()).isEqualTo(1707);
        assertThat(Files.exists(uploadsRoot.resolve(photo.getFilePath()))).isTrue();
        assertThat(Files.exists(uploadsRoot.resolve(photo.getThumbnailPath()))).isTrue();
    }

    @Test
    void create_storesEveryPhotoAsAWebpPair_andPersistsItsThumbnailAndSize() throws Exception {
        String email = "files-create@example.com";

        submissionService.create(
                email,
                request(section("general", "Text.", photo("a"), photo("b"))),
                Map.of("a", landscapePng("a"), "b", landscapePng("b")));

        List<Photo> photos = sectionFor(reload(email), "general").getPhotos();
        assertThat(photos).hasSize(2);
        photos.forEach(this::assertStoredAsWebpPair);
        assertThat(uploadedFiles()).hasSize(4);
    }

    @Test
    void edit_photoAddedToAnExistingSection_persistsItsThumbnailAndSize() {
        String email = "files-edit-add@example.com";
        submissionService.create(email, request(section("general", "Text.")), Map.of());

        submissionService.edit(
                email, request(section("general", "Text.", photo("new"))), Map.of("new", landscapePng("new")));

        assertThat(sectionFor(reload(email), "general").getPhotos())
                .singleElement()
                .satisfies(this::assertStoredAsWebpPair);
    }

    @Test
    void edit_photoInANewlyAddedSection_persistsItsThumbnailAndSize() {
        String email = "files-edit-new-section@example.com";
        submissionService.create(email, request(section("general", "Text.")), Map.of());

        submissionService.edit(
                email,
                request(section("general", "Text."), section("academics_teaching", "New.", photo("new"))),
                Map.of("new", landscapePng("new")));

        assertThat(sectionFor(reload(email), "academics_teaching").getPhotos())
                .singleElement()
                .satisfies(this::assertStoredAsWebpPair);
    }

    @Test
    void edit_keptPhoto_identifiedByItsFullSizeUrl_keepsItsFilesAndSize() throws Exception {
        String email = "files-edit-keep@example.com";
        submissionService.create(
                email, request(section("general", "Text.", photo("a"))), Map.of("a", landscapePng("a")));
        Photo before = sectionFor(reload(email), "general").getPhotos().get(0);
        List<String> filesBefore = uploadedFiles();

        submissionService.edit(
                email, request(section("general", "Text.", photo("/uploads/" + before.getFilePath()))), Map.of());

        Photo after = sectionFor(reload(email), "general").getPhotos().get(0);
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getThumbnailPath()).isEqualTo(before.getThumbnailPath());
        assertThat(after.getWidth()).isEqualTo(2560);
        assertThat(uploadedFiles()).isEqualTo(filesBefore);
    }

    @Test
    void edit_droppedPhoto_deletesBothItsFiles() throws Exception {
        String email = "files-edit-drop@example.com";
        submissionService.create(
                email, request(section("general", "Text.", photo("a"))), Map.of("a", landscapePng("a")));
        assertThat(uploadedFiles()).hasSize(2);

        submissionService.edit(email, request(section("general", "Text.")), Map.of());

        assertThat(sectionFor(reload(email), "general").getPhotos()).isEmpty();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void edit_replacedPhoto_deletesTheOldPairAndKeepsOnlyTheNewOne() throws Exception {
        String email = "files-edit-replace@example.com";
        submissionService.create(
                email, request(section("general", "Text.", photo("a"))), Map.of("a", landscapePng("a")));

        submissionService.edit(
                email, request(section("general", "Text.", photo("b"))), Map.of("b", landscapePng("b")));

        Photo replacement = sectionFor(reload(email), "general").getPhotos().get(0);
        assertThat(uploadedFiles())
                .containsExactlyInAnyOrder(replacement.getFilePath(), replacement.getThumbnailPath());
    }

    @Test
    void edit_removedSection_deletesBothFilesOfEachOfItsPhotos() throws Exception {
        String email = "files-edit-remove-section@example.com";
        submissionService.create(
                email,
                request(section("general", "Text."), section("academics_teaching", "Pics.", photo("a"), photo("b"))),
                Map.of("a", landscapePng("a"), "b", landscapePng("b")));
        assertThat(uploadedFiles()).hasSize(4);

        submissionService.edit(email, request(section("general", "Text.")), Map.of());

        assertThat(reload(email).getSections()).hasSize(1);
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void edit_droppedLegacyPhotoWithoutThumbnail_deletesItsSingleFile() throws Exception {
        String email = "files-edit-legacy@example.com";
        submissionService.create(email, request(section("general", "Text.")), Map.of());
        Testimonial testimonial = reload(email);
        TestimonialSection general = sectionFor(testimonial, "general");
        general.getPhotos().add(
                Photo.builder().section(general).filePath("legacy.jpeg").displayOrder(0).build());
        testimonialRepository.saveAndFlush(testimonial);
        Files.writeString(uploadsRoot.resolve("legacy.jpeg"), "legacy original");

        submissionService.edit(email, request(section("general", "Text.")), Map.of());

        assertThat(sectionFor(reload(email), "general").getPhotos()).isEmpty();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void edit_keptLegacyPhoto_staysUntouched() throws Exception {
        String email = "files-edit-keep-legacy@example.com";
        submissionService.create(email, request(section("general", "Text.")), Map.of());
        Testimonial testimonial = reload(email);
        TestimonialSection general = sectionFor(testimonial, "general");
        general.getPhotos().add(
                Photo.builder().section(general).filePath("legacy.jpeg").displayOrder(0).build());
        testimonialRepository.saveAndFlush(testimonial);
        Files.writeString(uploadsRoot.resolve("legacy.jpeg"), "legacy original");

        submissionService.edit(
                email, request(section("general", "Text.", photo("/uploads/legacy.jpeg"))), Map.of());

        Photo kept = sectionFor(reload(email), "general").getPhotos().get(0);
        assertThat(kept.getFilePath()).isEqualTo("legacy.jpeg");
        assertThat(kept.getThumbnailPath()).isNull();
        assertThat(kept.getWidth()).isNull();
        assertThat(uploadedFiles()).containsExactly("legacy.jpeg");
    }
}
