package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.LegacyPhotoBackfill.BackfillSummary;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one-off startup conversion of photos stored before the WebP pipeline:
 * each legacy photo (no thumbnail) is converted, its row re-pointed at the
 * new files, and only then its original deleted; anything that can't be
 * converted is logged and left exactly as it was. Nothing else about the
 * testimonial changes.
 *
 * <p>Not {@code @Transactional}: the backfill commits one transaction per
 * photo, so test data is committed and deleted again afterwards. Files live
 * in this test's own uploads root, so other test classes' leftover rows
 * (whose files don't exist here) can only ever count as failures.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LegacyPhotoBackfillTest {

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LegacyPhotoBackfill springConfiguredBackfill;

    @TempDir
    Path uploadsRoot;

    private final List<Long> createdTestimonialIds = new ArrayList<>();

    @AfterEach
    void deleteCreatedTestimonials() {
        createdTestimonialIds.forEach(testimonialRepository::deleteById);
    }

    private PhotoStorageService storage(PhotoStorageProperties properties) {
        return new PhotoStorageService(properties, new PhotoUrlResolver(), new PhotoImageProcessor(properties));
    }

    private LegacyPhotoBackfill backfill(boolean enabled) {
        PhotoStorageProperties properties = TestPhotoStorage.properties(uploadsRoot, enabled);
        return new LegacyPhotoBackfill(photoRepository, storage(properties), transactionManager, properties);
    }

    private static final Instant CREATED_AT = Instant.parse("2025-03-01T10:00:00Z");
    private static final Instant REVIEWED_AT = Instant.parse("2025-03-02T11:00:00Z");

    /** An approved testimonial with one section and the given (legacy or converted) photos. */
    private Testimonial approvedTestimonialWith(Photo... photos) {
        String email = "backfill-" + UUID.randomUUID() + "@example.com";
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(CREATED_AT)
                .reviewedAt(REVIEWED_AT)
                .identityModified(false)
                .scoreModified(true)
                .build();
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Text.")
                .modified(false)
                .build();
        for (Photo photo : photos) {
            photo.setSection(section);
            section.getPhotos().add(photo);
        }
        t.getSections().add(section);
        Testimonial saved = testimonialRepository.saveAndFlush(t);
        createdTestimonialIds.add(saved.getId());
        return saved;
    }

    private static Photo legacyPhoto(String filePath, int displayOrder) {
        return Photo.builder().filePath(filePath).displayOrder(displayOrder).build();
    }

    private Photo reload(Photo photo) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> photoRepository.findById(photo.getId()).orElseThrow());
    }

    private Photo photoWithPath(Testimonial testimonial, String filePath) {
        return testimonial.getSections().get(0).getPhotos().stream()
                .filter(p -> filePath.equals(p.getFilePath()))
                .findFirst()
                .orElseThrow();
    }

    private List<String> uploadedFiles() throws IOException {
        try (Stream<Path> files = Files.list(uploadsRoot)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void writeLegacyJpeg(String name) throws IOException {
        // Stored landscape (80x40) with EXIF "rotate 90 CW" and GPS, like a phone photo.
        Files.write(uploadsRoot.resolve(name), TestImages.jpegWithExif(TestImages.quadrants(80, 40), 6, true));
    }

    // -- conversion --

    @Test
    void legacyJpeg_isConverted_rowRepointed_andOriginalDeleted() throws Exception {
        writeLegacyJpeg("legacy.jpeg");
        Testimonial testimonial = approvedTestimonialWith(legacyPhoto("legacy.jpeg", 0));
        Photo before = photoWithPath(testimonial, "legacy.jpeg");

        BackfillSummary summary = backfill(true).backfill();

        assertThat(summary.converted()).isEqualTo(1);
        Photo after = reload(before);
        assertThat(after.getFilePath()).matches("[0-9a-f-]{36}\\.webp");
        assertThat(after.getThumbnailPath()).isEqualTo(after.getFilePath().replace(".webp", "-thumb.webp"));
        assertThat(after.getWidth()).isEqualTo(40);
        assertThat(after.getHeight()).isEqualTo(80);
        assertThat(after.getDisplayOrder()).isZero();
        assertThat(uploadedFiles()).containsExactlyInAnyOrder(after.getFilePath(), after.getThumbnailPath());
        BufferedImage full = TestImages.decode(Files.readAllBytes(uploadsRoot.resolve(after.getFilePath())));
        assertThat(full.getWidth()).isEqualTo(40);
        assertThat(full.getHeight()).isEqualTo(80);
    }

    @Test
    void conversion_changesNothingElseAboutTheTestimonial() throws Exception {
        writeLegacyJpeg("legacy.jpeg");
        Testimonial testimonial = approvedTestimonialWith(legacyPhoto("legacy.jpeg", 0));

        backfill(true).backfill();

        Testimonial after = new TransactionTemplate(transactionManager).execute(status -> {
            Testimonial loaded = testimonialRepository.findById(testimonial.getId()).orElseThrow();
            loaded.getSections().forEach(s -> s.getPhotos().size());
            return loaded;
        });
        assertThat(after.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(after.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(after.getReviewedAt()).isEqualTo(REVIEWED_AT);
        assertThat(after.getRejectedAt()).isNull();
        assertThat(after.isIdentityModified()).isFalse();
        assertThat(after.isScoreModified()).isTrue();
        assertThat(after.getSections()).singleElement().satisfies(section -> {
            assertThat(section.isModified()).isFalse();
            assertThat(section.getAnswerText()).isEqualTo("Text.");
            assertThat(section.getPhotos()).singleElement()
                    .satisfies(photo -> assertThat(photo.getThumbnailPath()).isNotNull());
        });
    }

    @Test
    void alreadyConvertedPhoto_isLeftAlone() throws Exception {
        Files.writeString(uploadsRoot.resolve("done.webp"), "full");
        Files.writeString(uploadsRoot.resolve("done-thumb.webp"), "thumb");
        Testimonial testimonial = approvedTestimonialWith(Photo.builder()
                .filePath("done.webp")
                .thumbnailPath("done-thumb.webp")
                .width(10)
                .height(20)
                .displayOrder(0)
                .build());

        BackfillSummary summary = backfill(true).backfill();

        assertThat(summary.converted()).isZero();
        Photo after = reload(photoWithPath(testimonial, "done.webp"));
        assertThat(after.getFilePath()).isEqualTo("done.webp");
        assertThat(after.getWidth()).isEqualTo(10);
        assertThat(uploadedFiles()).containsExactly("done-thumb.webp", "done.webp");
    }

    // -- failures: logged, left untouched, the rest still converted --

    @Test
    void missingAndCorruptFiles_areLoggedAndLeftUntouched_andLaterPhotosAreStillConverted(CapturedOutput output)
            throws Exception {
        byte[] corruptBytes = "definitely not an image".getBytes(StandardCharsets.UTF_8);
        Files.write(uploadsRoot.resolve("corrupt.jpeg"), corruptBytes);
        Testimonial failing =
                approvedTestimonialWith(legacyPhoto("missing.jpeg", 0), legacyPhoto("corrupt.jpeg", 1));
        writeLegacyJpeg("good.jpeg");
        Testimonial good = approvedTestimonialWith(legacyPhoto("good.jpeg", 0));
        Photo missing = photoWithPath(failing, "missing.jpeg");
        Photo corrupt = photoWithPath(failing, "corrupt.jpeg");
        assertThat(photoWithPath(good, "good.jpeg").getId()).isGreaterThan(corrupt.getId());

        BackfillSummary summary = backfill(true).backfill();

        assertThat(summary.converted()).isEqualTo(1);
        assertThat(summary.failed()).isGreaterThanOrEqualTo(2);
        assertThat(summary.total()).isEqualTo(summary.converted() + summary.failed());
        for (Photo untouched : List.of(missing, corrupt)) {
            Photo after = reload(untouched);
            assertThat(after.getFilePath()).isEqualTo(untouched.getFilePath());
            assertThat(after.getThumbnailPath()).isNull();
            assertThat(after.getWidth()).isNull();
        }
        assertThat(Files.readAllBytes(uploadsRoot.resolve("corrupt.jpeg"))).isEqualTo(corruptBytes);
        assertThat(uploadedFiles()).filteredOn(name -> name.endsWith(".webp")).hasSize(2);
        assertThat(uploadedFiles()).doesNotContain("good.jpeg");
        assertThat(output.getOut())
                .containsPattern("WARN.*photo " + missing.getId() + "\\b.*missing\\.jpeg")
                .containsPattern("WARN.*photo " + corrupt.getId() + "\\b.*corrupt\\.jpeg")
                .contains("Legacy photo backfill finished");
    }

    @Test
    void photoChangedWhileBeingConverted_keepsItsNewState_andTheFreshFilesAreRemovedAgain() throws Exception {
        writeLegacyJpeg("legacy.jpeg");
        Testimonial testimonial = approvedTestimonialWith(legacyPhoto("legacy.jpeg", 0));
        Photo photo = photoWithPath(testimonial, "legacy.jpeg");
        PhotoStorageProperties properties = TestPhotoStorage.properties(uploadsRoot, true);
        PhotoStorageService storage = spy(storage(properties));
        doAnswer(invocation -> {
            // A visitor's edit replaces the photo's file while it is being converted.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Photo current = photoRepository.findById(photo.getId()).orElseThrow();
                current.setFilePath("edited.webp");
                current.setThumbnailPath("edited-thumb.webp");
            });
            return invocation.callRealMethod();
        }).when(storage).convertLegacy(anyString());

        BackfillSummary summary =
                new LegacyPhotoBackfill(photoRepository, storage, transactionManager, properties).backfill();

        assertThat(summary.converted()).isZero();
        Photo after = reload(photo);
        assertThat(after.getFilePath()).isEqualTo("edited.webp");
        assertThat(after.getThumbnailPath()).isEqualTo("edited-thumb.webp");
        assertThat(uploadedFiles()).containsExactly("legacy.jpeg");
    }

    // -- idempotence, paging, switch --

    @Test
    void secondRun_convertsNothing_andChangesNoFile() throws Exception {
        writeLegacyJpeg("legacy.jpeg");
        Testimonial testimonial = approvedTestimonialWith(legacyPhoto("legacy.jpeg", 0));
        LegacyPhotoBackfill backfill = backfill(true);
        backfill.backfill();
        Photo afterFirst = reload(photoWithPath(testimonial, "legacy.jpeg"));
        List<String> filesAfterFirst = uploadedFiles();
        byte[] fullAfterFirst = Files.readAllBytes(uploadsRoot.resolve(afterFirst.getFilePath()));

        BackfillSummary second = backfill.backfill();

        assertThat(second.converted()).isZero();
        Photo afterSecond = reload(afterFirst);
        assertThat(afterSecond.getFilePath()).isEqualTo(afterFirst.getFilePath());
        assertThat(afterSecond.getThumbnailPath()).isEqualTo(afterFirst.getThumbnailPath());
        assertThat(uploadedFiles()).isEqualTo(filesAfterFirst);
        assertThat(Files.readAllBytes(uploadsRoot.resolve(afterFirst.getFilePath()))).isEqualTo(fullAfterFirst);
    }

    @Test
    void morePhotosThanOneBatch_withFailuresInBetween_areAllVisitedExactlyOnce() throws Exception {
        List<Photo> photos = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i % 2 == 0) {
                writeLegacyJpeg("legacy-" + i + ".jpeg");
            }
            photos.add(legacyPhoto("legacy-" + i + ".jpeg", i));
        }
        approvedTestimonialWith(photos.toArray(Photo[]::new));
        PhotoStorageProperties properties = TestPhotoStorage.properties(uploadsRoot, true);

        BackfillSummary summary =
                new LegacyPhotoBackfill(photoRepository, storage(properties), transactionManager, properties, 2)
                        .backfill();

        assertThat(summary.converted()).isEqualTo(3);
        assertThat(summary.failed()).isGreaterThanOrEqualTo(2);
        assertThat(uploadedFiles()).hasSize(6).allMatch(name -> name.endsWith(".webp"));
    }

    @Test
    void run_whenEnabled_convertsLegacyPhotos() throws Exception {
        writeLegacyJpeg("legacy.jpeg");
        Testimonial testimonial = approvedTestimonialWith(legacyPhoto("legacy.jpeg", 0));

        backfill(true).run(new DefaultApplicationArguments());

        assertThat(reload(photoWithPath(testimonial, "legacy.jpeg")).getThumbnailPath()).isNotNull();
    }

    @Test
    void run_whenDisabled_doesNothing() throws Exception {
        writeLegacyJpeg("legacy.jpeg");
        Testimonial testimonial = approvedTestimonialWith(legacyPhoto("legacy.jpeg", 0));

        backfill(false).run(new DefaultApplicationArguments());

        Photo after = reload(photoWithPath(testimonial, "legacy.jpeg"));
        assertThat(after.getFilePath()).isEqualTo("legacy.jpeg");
        assertThat(after.getThumbnailPath()).isNull();
        assertThat(uploadedFiles()).containsExactly("legacy.jpeg");
    }

    @Test
    void noLegacyPhotosAtAll_isANoOp() throws Exception {
        PhotoStorageProperties properties = TestPhotoStorage.properties(uploadsRoot, true);
        PhotoRepository empty = org.mockito.Mockito.mock(PhotoRepository.class);

        BackfillSummary summary =
                new LegacyPhotoBackfill(empty, storage(properties), transactionManager, properties).backfill();

        assertThat(summary.converted()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.total()).isZero();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void theAppsOwnBackfill_isRegisteredAsAStartupRunner_andDisabledInTests() {
        assertThat(springConfiguredBackfill).isInstanceOf(org.springframework.boot.ApplicationRunner.class);
    }
}
