package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests with real temp directories and real (not hand-faked) image
 * bytes, so format sniffing and the WebP conversion are genuinely exercised
 * (NFR-UPLOAD-SPOOFING, decision 2).
 */
class PhotoStorageServiceTest {

    private static final String UNSUPPORTED =
            "Unsupported image format. Please upload JPEG, PNG, WebP, GIF, TIFF or BMP.";

    @TempDir
    Path root;

    private PhotoStorageService serviceWithLimit(long maxBytes) {
        PhotoStorageProperties properties = TestPhotoStorage.properties(root, 20, maxBytes);
        return new PhotoStorageService(
                properties,
                new PhotoUrlResolver(),
                new PhotoImageProcessor(properties),
                new PhotoFileDeleter(properties));
    }

    private PhotoStorageService service() {
        return serviceWithLimit(TestPhotoStorage.DEFAULT_MAX_PHOTO_SIZE_BYTES);
    }

    /** A service whose next stored photo gets the given id instead of a random UUID. */
    private PhotoStorageService serviceWithFixedId(String id) {
        PhotoStorageProperties properties = TestPhotoStorage.properties(root);
        return new PhotoStorageService(
                properties,
                new PhotoUrlResolver(),
                new PhotoImageProcessor(properties),
                new PhotoFileDeleter(properties),
                () -> id);
    }

    private static MockMultipartFile upload(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("photo", filename, contentType, bytes);
    }

    private static byte[] pngBytes(int width, int height) {
        return TestImages.png(TestImages.solid(width, height, TestImages.BLUE));
    }

    // -- store --

    @Test
    void store_writesAFullWebpAndAThumbnailWebp_underUuidNames() throws Exception {
        StoredPhoto stored = service().store(upload("cat.png", "image/png", pngBytes(3000, 2000)));

        assertThat(stored.filePath()).matches("[0-9a-f-]{36}\\.webp");
        assertThat(stored.thumbnailPath()).isEqualTo(stored.filePath().replace(".webp", "-thumb.webp"));
        assertThat(stored.filePath()).doesNotContain("cat");
        assertThat(TestImages.isWebp(Files.readAllBytes(root.resolve(stored.filePath())))).isTrue();
        assertThat(TestImages.isWebp(Files.readAllBytes(root.resolve(stored.thumbnailPath())))).isTrue();
        assertThat(stored.width()).isEqualTo(2560);
        assertThat(stored.height()).isEqualTo(1707);
        BufferedImage thumb = TestImages.decode(Files.readAllBytes(root.resolve(stored.thumbnailPath())));
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(640);
        assertThat(rootContents()).containsExactlyInAnyOrder(stored.filePath(), stored.thumbnailPath());
    }

    @Test
    void store_neverKeepsTheOriginalBytes() throws Exception {
        byte[] original = TestImages.jpegWithExif(TestImages.quadrants(80, 40), 6, true);

        StoredPhoto stored = service().store(upload("phone.jpg", "image/jpeg", original));

        assertThat(rootContents()).hasSize(2).allMatch(name -> name.endsWith(".webp"));
        assertThat(Files.readAllBytes(root.resolve(stored.filePath()))).isNotEqualTo(original);
        assertThat(stored.width()).isEqualTo(40);
        assertThat(stored.height()).isEqualTo(80);
    }

    @Test
    void store_detectsTheFormatFromTheBytes_notTheDeclaredNameOrContentType() {
        byte[] jpeg = TestImages.jpeg(TestImages.solid(20, 10, TestImages.RED));

        StoredPhoto stored = service().store(upload("cat.png", "image/png", jpeg));

        assertThat(stored.filePath()).endsWith(".webp");
        assertThat(stored.width()).isEqualTo(20);
    }

    @Test
    void store_twoUploadsOfIdenticalBytes_produceDistinctFiles() {
        byte[] bytes = pngBytes(10, 10);
        PhotoStorageService service = service();

        StoredPhoto first = service.store(upload("a.png", "image/png", bytes));
        StoredPhoto second = service.store(upload("b.png", "image/png", bytes));

        assertThat(List.of(first.filePath(), first.thumbnailPath()))
                .doesNotContainAnyElementsOf(List.of(second.filePath(), second.thumbnailPath()));
    }

    @Test
    void store_createsTheUploadsRootIfMissing() {
        Path nested = root.resolve("not-yet").resolve("there");
        PhotoStorageProperties properties = TestPhotoStorage.properties(nested);
        PhotoStorageService service = new PhotoStorageService(
                properties,
                new PhotoUrlResolver(),
                new PhotoImageProcessor(properties),
                new PhotoFileDeleter(properties));

        StoredPhoto stored = service.store(upload("a.png", "image/png", pngBytes(10, 10)));

        assertThat(Files.exists(nested.resolve(stored.filePath()))).isTrue();
    }

    @Test
    void store_textFileClaimingToBeAJpeg_isRejectedAndNothingIsWritten() throws Exception {
        MockMultipartFile spoofed = upload(
                "fake.jpg", "image/jpeg", "not actually an image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service().store(spoofed))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_heicFile_isRejectedAsUnsupported() throws Exception {
        MockMultipartFile heic = upload("IMG_0001.HEIC", "image/heic", TestImages.isoBmffFile("heic"));

        assertThatThrownBy(() -> service().store(heic))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_emptyFile_isRejectedAsUnsupported() {
        assertThatThrownBy(() -> service().store(upload("empty.png", "image/png", new byte[0])))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
    }

    // -- size limit: checked first, before the bytes are even looked at --

    @Test
    void store_fileOfExactlyTheDefaultLimit_passesTheSizeCheck() {
        // 20 MB of zeros: not an image, so it only fails AFTER the size check.
        MockMultipartFile atLimit =
                upload("big.jpg", "image/jpeg", new byte[(int) TestPhotoStorage.DEFAULT_MAX_PHOTO_SIZE_BYTES]);

        assertThatThrownBy(() -> service().store(atLimit))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
    }

    @Test
    void store_fileOneByteOverTheDefaultLimit_isRejectedWithTheLimitInMegabytes() throws Exception {
        MockMultipartFile overLimit =
                upload("big.jpg", "image/jpeg", new byte[(int) TestPhotoStorage.DEFAULT_MAX_PHOTO_SIZE_BYTES + 1]);

        assertThatThrownBy(() -> service().store(overLimit))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage("Photo exceeds the maximum allowed file size (20 MB).");
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_realImageExactlyAtAConfiguredLimit_isStored() {
        byte[] bytes = pngBytes(10, 10);

        StoredPhoto stored = serviceWithLimit(bytes.length).store(upload("a.png", "image/png", bytes));

        assertThat(stored.width()).isEqualTo(10);
    }

    @Test
    void store_realImageOneByteOverAConfiguredLimit_isRejectedEvenThoughItIsValid() throws Exception {
        byte[] bytes = pngBytes(10, 10);

        assertThatThrownBy(() -> serviceWithLimit(bytes.length - 1).store(upload("a.png", "image/png", bytes)))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessageStartingWith("Photo exceeds the maximum allowed file size (");
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_sizeMessage_namesAFractionalLimitToOneDecimal() {
        MockMultipartFile tooBig = upload("a.png", "image/png", new byte[1_600_000]);

        assertThatThrownBy(() -> serviceWithLimit(1_572_864L).store(tooBig))
                .hasMessage("Photo exceeds the maximum allowed file size (1.5 MB).");
    }

    // -- no orphan files when a write fails --

    @Test
    void store_thumbnailWriteFails_leavesNoFullSizeFileBehind() throws Exception {
        Files.createDirectory(root.resolve("fixed-id-thumb.webp"));

        assertThatThrownBy(() -> serviceWithFixedId("fixed-id").store(upload("a.png", "image/png", pngBytes(10, 10))))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.exists(root.resolve("fixed-id.webp"))).isFalse();
        assertThat(Files.isDirectory(root.resolve("fixed-id-thumb.webp"))).as("not ours, left alone").isTrue();
    }

    @Test
    void store_fullSizeWriteFails_writesNoThumbnail() throws Exception {
        Files.createDirectory(root.resolve("fixed-id.webp"));

        assertThatThrownBy(() -> serviceWithFixedId("fixed-id").store(upload("a.png", "image/png", pngBytes(10, 10))))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.exists(root.resolve("fixed-id-thumb.webp"))).isFalse();
        assertThat(Files.isDirectory(root.resolve("fixed-id.webp"))).as("not ours, left alone").isTrue();
    }

    // -- convertLegacy: re-encoding a photo stored before WebP --

    @Test
    void convertLegacy_writesANewWebpPair_andLeavesTheOriginalInPlace() throws Exception {
        byte[] original = TestImages.jpegWithExif(TestImages.quadrants(80, 40), 6, true);
        Files.write(root.resolve("legacy.jpeg"), original);

        StoredPhoto converted = service().convertLegacy("legacy.jpeg");

        assertThat(converted.width()).isEqualTo(40);
        assertThat(converted.height()).isEqualTo(80);
        assertThat(TestImages.isWebp(Files.readAllBytes(root.resolve(converted.filePath())))).isTrue();
        assertThat(TestImages.isWebp(Files.readAllBytes(root.resolve(converted.thumbnailPath())))).isTrue();
        assertThat(Files.readAllBytes(root.resolve("legacy.jpeg"))).isEqualTo(original);
    }

    @Test
    void convertLegacy_missingFile_throwsAndWritesNothing() throws Exception {
        assertThatThrownBy(() -> service().convertLegacy("gone.jpeg")).isInstanceOf(UncheckedIOException.class);

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void convertLegacy_undecodableFile_throwsAndWritesNothingNew() throws Exception {
        Files.writeString(root.resolve("broken.png"), "not an image");

        assertThatThrownBy(() -> service().convertLegacy("broken.png"))
                .isInstanceOf(SubmissionValidationException.class);

        assertThat(rootContents()).containsExactly("broken.png");
    }

    // -- delete --

    @Test
    void delete_photo_removesBothTheFullSizeFileAndTheThumbnail() throws Exception {
        PhotoStorageService service = service();
        StoredPhoto stored = service.store(upload("a.png", "image/png", pngBytes(10, 10)));

        service.delete(photo(stored.filePath(), stored.thumbnailPath()));

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void delete_legacyPhotoWithoutThumbnail_removesItsFile() throws Exception {
        Files.writeString(root.resolve("legacy.jpeg"), "x");

        service().delete(photo("legacy.jpeg", null));

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void delete_photoWhoseFilesAreAlreadyGone_doesNotThrow() {
        PhotoStorageService service = service();

        assertThatCode(() -> service.delete(photo("never.webp", "never-thumb.webp"))).doesNotThrowAnyException();
    }

    @Test
    void delete_singleFile_removesOnlyThatFile() throws Exception {
        Files.writeString(root.resolve("a.webp"), "x");
        Files.writeString(root.resolve("a-thumb.webp"), "x");

        service().delete("a.webp");

        assertThat(rootContents()).containsExactly("a-thumb.webp");
    }

    @Test
    void delete_nonExistentSingleFile_doesNotThrow() {
        PhotoStorageService service = service();

        assertThatCode(() -> service.delete("never-existed.png")).doesNotThrowAnyException();
    }

    // -- inside a transaction: stored files go on rollback, deletes wait for the commit (BL-017) --

    @Test
    void store_inATransactionThatCommits_keepsBothFiles() throws Exception {
        PhotoStorageService service = service();

        List<StoredPhoto> stored = new ArrayList<>();

        committed(() -> stored.add(service.store(upload("a.png", "image/png", pngBytes(10, 10)))));

        assertThat(rootContents()).containsExactlyInAnyOrder(stored.get(0).filePath(), stored.get(0).thumbnailPath());
    }

    @Test
    void store_inATransactionThatRollsBack_removesBothFiles() throws Exception {
        PhotoStorageService service = service();

        rolledBack(() -> {
            StoredPhoto stored = service.store(upload("a.png", "image/png", pngBytes(10, 10)));
            assertThat(rootContents()).as("kept until the rollback")
                    .containsExactlyInAnyOrder(stored.filePath(), stored.thumbnailPath());
        });

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_severalPhotosInARolledBackTransaction_removesEveryOnesFiles_andNoOtherFile() throws Exception {
        PhotoStorageService service = service();
        StoredPhoto earlier = service.store(upload("earlier.png", "image/png", pngBytes(10, 10)));
        Files.writeString(root.resolve("unrelated.jpeg"), "not ours");

        rolledBack(() -> {
            service.store(upload("a.png", "image/png", pngBytes(10, 10)));
            service.store(upload("b.png", "image/png", pngBytes(12, 12)));
            service.store(upload("c.png", "image/png", pngBytes(14, 14)));
        });

        assertThat(rootContents())
                .containsExactlyInAnyOrder(earlier.filePath(), earlier.thumbnailPath(), "unrelated.jpeg");
    }

    @Test
    void store_inATransactionWhoseCommitFailsAndIsRolledBack_removesBothFiles() throws Exception {
        PhotoStorageService service = service();
        TransactionTemplate failingCommit = new TransactionTemplate(
                new NoResourceTransactionManager(new IllegalStateException("flush failed at commit")));

        assertThatThrownBy(() -> failingCommit.executeWithoutResult(
                        status -> service.store(upload("a.png", "image/png", pngBytes(10, 10)))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_inATransactionWhoseCommitOutcomeIsUnknown_keepsBothFiles() throws Exception {
        PhotoStorageService service = service();
        TransactionTemplate unknownOutcome = new TransactionTemplate(
                new NoResourceTransactionManager(new TransactionSystemException("connection lost during commit")));
        List<StoredPhoto> stored = new ArrayList<>();

        assertThatThrownBy(() -> unknownOutcome.executeWithoutResult(
                        status -> stored.add(service.store(upload("a.png", "image/png", pngBytes(10, 10))))))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(rootContents()).as("the row may have been committed, so its files stay")
                .containsExactlyInAnyOrder(stored.get(0).filePath(), stored.get(0).thumbnailPath());
    }

    @Test
    void store_rolledBackAfterItsFullSizeFileIsAlreadyGone_stillRemovesTheThumbnail() throws Exception {
        PhotoStorageService service = service();

        rolledBack(() -> {
            StoredPhoto stored = service.store(upload("a.png", "image/png", pngBytes(10, 10)));
            Files.delete(root.resolve(stored.filePath()));
        });

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void store_thumbnailWriteFailsInATransaction_removesTheFullSizeFileRightAway() throws Exception {
        Files.createDirectory(root.resolve("fixed-id-thumb.webp"));
        PhotoStorageService service = serviceWithFixedId("fixed-id");

        committed(() -> {
            assertThatThrownBy(() -> service.store(upload("a.png", "image/png", pngBytes(10, 10))))
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(Files.exists(root.resolve("fixed-id.webp"))).as("gone before the commit").isFalse();
        });

        assertThat(Files.isDirectory(root.resolve("fixed-id-thumb.webp"))).as("not ours, left alone").isTrue();
    }

    @Test
    void convertLegacy_inATransactionThatRollsBack_removesTheNewPair_andKeepsTheOriginal() throws Exception {
        Files.write(root.resolve("legacy.png"), pngBytes(10, 10));
        PhotoStorageService service = service();

        rolledBack(() -> service.convertLegacy("legacy.png"));

        assertThat(rootContents()).containsExactly("legacy.png");
    }

    @Test
    void delete_photo_inATransaction_keepsBothFilesUntilTheCommit_thenRemovesThem() throws Exception {
        Files.writeString(root.resolve("a.webp"), "full");
        Files.writeString(root.resolve("a-thumb.webp"), "thumb");
        PhotoStorageService service = service();

        committed(() -> {
            service.delete(photo("a.webp", "a-thumb.webp"));
            assertThat(rootContents()).as("kept until the commit").containsExactlyInAnyOrder("a.webp", "a-thumb.webp");
        });

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void delete_photo_inATransactionThatRollsBack_keepsBothFiles() throws Exception {
        Files.writeString(root.resolve("a.webp"), "full");
        Files.writeString(root.resolve("a-thumb.webp"), "thumb");
        PhotoStorageService service = service();

        rolledBack(() -> service.delete(photo("a.webp", "a-thumb.webp")));

        assertThat(rootContents()).containsExactlyInAnyOrder("a.webp", "a-thumb.webp");
    }

    @Test
    void delete_photo_inATransaction_removesTheFilesItHadWhenDeleteWasCalled() throws Exception {
        Files.writeString(root.resolve("a.webp"), "full");
        Files.writeString(root.resolve("a-thumb.webp"), "thumb");
        Files.writeString(root.resolve("b.webp"), "other");
        PhotoStorageService service = service();

        committed(() -> {
            Photo photo = photo("a.webp", "a-thumb.webp");
            service.delete(photo);
            photo.setFilePath("b.webp");
            photo.setThumbnailPath(null);
        });

        assertThat(rootContents()).containsExactly("b.webp");
    }

    @Test
    void delete_legacyPhotoWithoutThumbnail_inATransaction_removesItsFileAfterTheCommit() throws Exception {
        Files.writeString(root.resolve("legacy.jpeg"), "x");
        PhotoStorageService service = service();

        committed(() -> {
            service.delete(photo("legacy.jpeg", null));
            assertThat(rootContents()).containsExactly("legacy.jpeg");
        });

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void delete_singleFile_inATransaction_waitsForTheCommit_andIsKeptOnRollback() throws Exception {
        Files.writeString(root.resolve("a.webp"), "x");
        Files.writeString(root.resolve("b.webp"), "x");
        PhotoStorageService service = service();

        rolledBack(() -> service.delete("a.webp"));
        committed(() -> {
            service.delete("b.webp");
            assertThat(rootContents()).containsExactlyInAnyOrder("a.webp", "b.webp");
        });

        assertThat(rootContents()).containsExactly("a.webp");
    }

    @Test
    void urlFor_prependsUploadsPrefix() {
        assertThat(service().urlFor("abc123.webp")).isEqualTo("/uploads/abc123.webp");
    }

    private static Photo photo(String filePath, String thumbnailPath) {
        return Photo.builder().filePath(filePath).thumbnailPath(thumbnailPath).displayOrder(0).build();
    }

    /** Runs {@code work} in a transaction that commits. */
    private static void committed(ThrowingRunnable work) {
        new TransactionTemplate(new NoResourceTransactionManager(null)).executeWithoutResult(status -> {
            try {
                work.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Runs {@code work} in a transaction that rolls back once it returns. */
    private static void rolledBack(ThrowingRunnable work) {
        new TransactionTemplate(new NoResourceTransactionManager(null)).executeWithoutResult(status -> {
            try {
                work.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            status.setRollbackOnly();
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Spring's real commit/rollback and synchronization machinery with no
     * resource behind it; {@code commitFailure}, if given, is what the commit
     * throws.
     */
    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        private final RuntimeException commitFailure;

        NoResourceTransactionManager(RuntimeException commitFailure) {
            this.commitFailure = commitFailure;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // nothing to begin
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }

    private List<String> rootContents() throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
