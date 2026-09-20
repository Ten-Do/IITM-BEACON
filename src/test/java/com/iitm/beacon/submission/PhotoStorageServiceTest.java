package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoStorageProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Unit tests with real temp directories and real (not hand-faked) image
 * bytes, so the {@code ImageIO}-based format sniffing is genuinely exercised
 * (NFR-UPLOAD-SPOOFING, decision 2).
 */
class PhotoStorageServiceTest {

    @TempDir
    Path root;

    private PhotoStorageService serviceWithLimit(long maxBytes) {
        return new PhotoStorageService(new PhotoStorageProperties(root.toString(), 20, maxBytes));
    }

    private PhotoStorageService service() {
        return serviceWithLimit(5_242_880L);
    }

    private static byte[] realPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] realJpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void store_realPngFile_writesItUnderRootWithUuidNameAndPngExtension() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());

        String stored = service().store(file);

        assertThat(stored).endsWith(".png");
        assertThat(stored).doesNotContain("cat");
        Path written = root.resolve(stored);
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readAllBytes(written)).isEqualTo(file.getBytes());
    }

    @Test
    void store_realJpegFile_returnsExtensionFromDetectedFormatNotDeclaredContentType() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("photo", "cat.png", "image/jpeg", realJpegBytes());

        String stored = service().store(file);

        // The JDK's own JPEG ImageReader reports its format name as "JPEG"
        // (-> ".jpeg") — the extension always comes from ImageIO's detected
        // format, never the client-declared filename/content-type (decision
        // 2), which this test deliberately mismatches on both counts.
        assertThat(stored).endsWith(".jpeg");
    }

    @Test
    void store_twoUploadsOfIdenticalBytes_produceTwoDistinctFilenames() throws Exception {
        byte[] bytes = realPngBytes();
        MockMultipartFile first = new MockMultipartFile("photo", "a.png", "image/png", bytes);
        MockMultipartFile second = new MockMultipartFile("photo", "b.png", "image/png", bytes);

        PhotoStorageService service = service();
        String storedFirst = service.store(first);
        String storedSecond = service.store(second);

        assertThat(storedFirst).isNotEqualTo(storedSecond);
    }

    @Test
    void store_nonImageFileClaimingImageContentType_isRejectedAndNothingIsWritten() throws Exception {
        MockMultipartFile spoofed = new MockMultipartFile(
                "photo", "fake.jpg", "image/jpeg", "not actually an image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service().store(spoofed)).isInstanceOf(SubmissionValidationException.class);

        assertThat(rootContentsOrEmpty()).isEmpty();
    }

    @Test
    void store_emptyFile_isRejectedAsNotAnImage() {
        MockMultipartFile empty = new MockMultipartFile("photo", "empty.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service().store(empty)).isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void store_fileExactlyAtSizeLimit_isAccepted() throws Exception {
        byte[] bytes = realPngBytes();
        MockMultipartFile file = new MockMultipartFile("photo", "cat.png", "image/png", bytes);

        String stored = serviceWithLimit(bytes.length).store(file);

        assertThat(stored).endsWith(".png");
    }

    @Test
    void store_oversizedFile_isRejectedBeforeAnyBytesAreWritten() throws Exception {
        byte[] bytes = realPngBytes();
        MockMultipartFile file = new MockMultipartFile("photo", "cat.png", "image/png", bytes);

        assertThatThrownBy(() -> serviceWithLimit(bytes.length - 1).store(file))
                .isInstanceOf(SubmissionValidationException.class);

        assertThat(rootContentsOrEmpty()).isEmpty();
    }

    @Test
    void delete_existingFile_removesIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());
        PhotoStorageService service = service();
        String stored = service.store(file);

        service.delete(stored);

        assertThat(Files.exists(root.resolve(stored))).isFalse();
    }

    @Test
    void delete_nonExistentFile_doesNotThrow() {
        PhotoStorageService service = service();

        assertThatCode(() -> service.delete("never-existed.png")).doesNotThrowAnyException();
    }

    @Test
    void urlFor_prependsUploadsPrefix() {
        assertThat(service().urlFor("abc123.png")).isEqualTo("/uploads/abc123.png");
    }

    private Stream<Path> rootContentsOrEmpty() throws IOException {
        if (!Files.exists(root)) {
            return Stream.empty();
        }
        return Files.list(root);
    }
}
