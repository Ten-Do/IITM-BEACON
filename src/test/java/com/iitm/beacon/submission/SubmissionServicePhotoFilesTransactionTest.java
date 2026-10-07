package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.SubmissionValidationException;
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
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Photo files follow the submission's transaction (BL-017): files stored by
 * a create or edit that rolls back — a later photo refused, or the save
 * failing — are removed again, and a photo an edit removes or replaces
 * loses its files only once the edit has committed, so a rollback never
 * loses a live file.
 *
 * <p>Not {@code @Transactional}: the point is real commits and rollbacks
 * (the hand-built service has no transactional proxy, so each call runs in
 * its own {@link TransactionTemplate}), and the committed testimonials are
 * deleted again afterwards. Files live in this test's own uploads root.
 */
@SpringBootTest
class SubmissionServicePhotoFilesTransactionTest {

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
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path uploadsRoot;

    private SubmissionService submissionService;
    private TransactionTemplate tx;
    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        submissionService = serviceSavingWith(testimonialRepository);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void deleteCommittedTestimonials() {
        tx.executeWithoutResult(status -> emails.forEach(email -> testimonialRepository
                .findByEmailLookupHash(emailLookupHashService.hash(email))
                .ifPresent(testimonialRepository::delete)));
    }

    // -- create --

    @Test
    void create_committed_keepsBothFilesOfEveryPhoto() throws Exception {
        String email = email("tx-create-commit");

        createCommitted(
                email,
                request(section("general", "Text.", photo("a"), photo("b"))),
                Map.of("a", png("a"), "b", png("b")));

        List<Photo> photos = photosOf(email);
        assertThat(photos).hasSize(2);
        assertThat(uploadedFiles()).containsExactlyInAnyOrderElementsOf(filesOf(photos)).hasSize(4);
    }

    @Test
    void create_whoseLaterPhotoIsRefused_rollsBack_leavingNoFileOfTheEarlierOnes() throws Exception {
        String email = email("tx-create-refused");

        assertThatThrownBy(() -> createCommitted(
                        email,
                        request(
                                section("general", "Text.", photo("a"), photo("b")),
                                section("academics_teaching", "Pics.", photo("bad"))),
                        Map.of("a", png("a"), "b", png("b"), "bad", notAnImage("bad"))))
                .isInstanceOf(SubmissionValidationException.class);

        assertThat(uploadedFiles()).isEmpty();
        assertThat(testimonialExists(email)).isFalse();
    }

    @Test
    void create_whoseSaveFails_rollsBack_leavingNoFile() throws Exception {
        String email = email("tx-create-save-fails");
        SubmissionService failingSave = serviceSavingWith(repositoryWhoseSaveFails());

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> failingSave.create(
                        email,
                        request(section("general", "Text.", photo("a"), photo("b"))),
                        Map.of("a", png("a"), "b", png("b")))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(uploadedFiles()).isEmpty();
        assertThat(testimonialExists(email)).isFalse();
    }

    // -- edit: a rollback keeps every live file, and removes the new ones --

    @Test
    void edit_thatReplacesAPhotoThenRefusesALaterOne_keepsTheOldFiles_andRemovesTheNewOnes() throws Exception {
        String email = email("tx-edit-refused");
        createCommitted(email, request(section("general", "Text.", photo("a"))), Map.of("a", png("a")));
        List<Photo> before = photosOf(email);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> submissionService.edit(
                        email,
                        request(
                                section("general", "Text.", photo("b")),
                                section("academics_teaching", "Pics.", photo("bad"))),
                        Map.of("b", png("b"), "bad", notAnImage("bad")))))
                .isInstanceOf(SubmissionValidationException.class);

        assertThat(photosOf(email)).extracting(Photo::getId).containsExactly(before.get(0).getId());
        assertThat(uploadedFiles()).containsExactlyInAnyOrderElementsOf(filesOf(before));
    }

    @Test
    void edit_thatRemovesASectionThenFailsToSave_keepsItsPhotoFiles() throws Exception {
        String email = email("tx-edit-save-fails");
        createCommitted(
                email,
                request(section("general", "Text."), section("academics_teaching", "Pics.", photo("a"), photo("b"))),
                Map.of("a", png("a"), "b", png("b")));
        List<Photo> before = photosOf(email);
        SubmissionService failingSave = serviceSavingWith(repositoryWhoseSaveFails());

        assertThatThrownBy(() -> tx.executeWithoutResult(
                        status -> failingSave.edit(email, request(section("general", "Text.")), Map.of())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(photosOf(email)).hasSize(2);
        assertThat(uploadedFiles()).containsExactlyInAnyOrderElementsOf(filesOf(before)).hasSize(4);
    }

    // -- edit: removed or replaced photos lose their files only once the edit has committed --

    @Test
    void edit_replacedPhoto_keepsTheOldFilesUntilTheCommit_thenOnlyTheNewPairIsLeft() throws Exception {
        String email = email("tx-edit-replace");
        createCommitted(email, request(section("general", "Text.", photo("a"))), Map.of("a", png("a")));
        List<String> oldFiles = filesOf(photosOf(email));

        tx.executeWithoutResult(status -> {
            submissionService.edit(email, request(section("general", "Text.", photo("b"))), Map.of("b", png("b")));
            try {
                assertThat(uploadedFiles()).as("old pair still there before the commit")
                        .containsAll(oldFiles)
                        .hasSize(4);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        List<Photo> after = photosOf(email);
        assertThat(after).hasSize(1);
        assertThat(uploadedFiles())
                .containsExactlyInAnyOrderElementsOf(filesOf(after))
                .doesNotContainAnyElementsOf(oldFiles);
    }

    @Test
    void edit_keptPhoto_keepsItsFilesThroughTheCommit() throws Exception {
        String email = email("tx-edit-keep");
        createCommitted(email, request(section("general", "Text.", photo("a"))), Map.of("a", png("a")));
        List<Photo> before = photosOf(email);

        tx.executeWithoutResult(status -> submissionService.edit(
                email, request(section("general", "Edited.", kept(before.get(0)))), Map.of()));

        assertThat(uploadedFiles()).containsExactlyInAnyOrderElementsOf(filesOf(before));
    }

    @Test
    void edit_removedSection_deletesBothFilesOfEachOfItsPhotosAfterTheCommit() throws Exception {
        String email = email("tx-edit-remove-section");
        createCommitted(
                email,
                request(section("general", "Text."), section("academics_teaching", "Pics.", photo("a"), photo("b"))),
                Map.of("a", png("a"), "b", png("b")));

        tx.executeWithoutResult(
                status -> submissionService.edit(email, request(section("general", "Text.")), Map.of()));

        assertThat(photosOf(email)).isEmpty();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void edit_droppedLegacyPhotoWithoutThumbnail_deletesItsSingleFileAfterTheCommit() throws Exception {
        String email = email("tx-edit-legacy");
        createCommitted(email, request(section("general", "Text.")), Map.of());
        tx.executeWithoutResult(status -> {
            TestimonialSection general = findTestimonial(email).orElseThrow().getSections().get(0);
            general.getPhotos().add(
                    Photo.builder().section(general).filePath("legacy.jpeg").displayOrder(0).build());
        });
        Files.writeString(uploadsRoot.resolve("legacy.jpeg"), "legacy original");

        tx.executeWithoutResult(
                status -> submissionService.edit(email, request(section("general", "Text.")), Map.of()));

        assertThat(photosOf(email)).isEmpty();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void edit_droppedPhotoWhoseFileIsAlreadyMissing_stillCommits_andRemovesTheRest() throws Exception {
        String email = email("tx-edit-missing");
        createCommitted(email, request(section("general", "Text.", photo("a"))), Map.of("a", png("a")));
        Photo before = photosOf(email).get(0);
        Files.delete(uploadsRoot.resolve(before.getFilePath()));

        tx.executeWithoutResult(
                status -> submissionService.edit(email, request(section("general", "Text.")), Map.of()));

        assertThat(photosOf(email)).isEmpty();
        assertThat(uploadedFiles()).isEmpty();
    }

    /** The service, storing photos in this test's uploads root and saving through {@code repository}. */
    private SubmissionService serviceSavingWith(TestimonialRepository repository) {
        PhotoStorageProperties properties = TestPhotoStorage.properties(uploadsRoot);
        return new SubmissionService(
                repository,
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

    /** The real repository, except that every save fails as a duplicate insert would. */
    private TestimonialRepository repositoryWhoseSaveFails() {
        TestimonialRepository failing =
                mock(TestimonialRepository.class, AdditionalAnswers.delegatesTo(testimonialRepository));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(failing)
                .save(any(Testimonial.class));
        return failing;
    }

    private String email(String name) {
        String email = name + "@example.com";
        emails.add(email);
        return email;
    }

    private static MockMultipartFile png(String part) {
        return new MockMultipartFile(
                part, part + ".png", "image/png", TestImages.png(TestImages.solid(20, 10, TestImages.BLUE)));
    }

    private static MockMultipartFile notAnImage(String part) {
        return new MockMultipartFile(
                part, part + ".png", "image/png", "not actually an image".getBytes(StandardCharsets.UTF_8));
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

    private static PhotoInput kept(Photo photo) {
        return photo("/uploads/" + photo.getFilePath());
    }

    private void createCommitted(
            String email, TestimonialSubmissionRequest request, Map<String, MultipartFile> parts) {
        tx.executeWithoutResult(status -> submissionService.create(email, request, parts));
    }

    /** Every photo row the testimonial has now, read in its own transaction. */
    private List<Photo> photosOf(String email) {
        return tx.execute(status -> findTestimonial(email)
                .map(testimonial -> testimonial.getSections().stream()
                        .flatMap(section -> section.getPhotos().stream())
                        .toList())
                .orElse(List.of()));
    }

    private Optional<Testimonial> findTestimonial(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email));
    }

    private boolean testimonialExists(String email) {
        return Boolean.TRUE.equals(tx.execute(status -> findTestimonial(email).isPresent()));
    }

    private static List<String> filesOf(List<Photo> photos) {
        return photos.stream()
                .flatMap(p -> Stream.of(p.getFilePath(), p.getThumbnailPath()))
                .filter(path -> path != null)
                .toList();
    }

    private List<String> uploadedFiles() throws IOException {
        try (Stream<Path> files = Files.list(uploadsRoot)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
