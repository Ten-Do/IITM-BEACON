package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.testsupport.SpoofedUploads;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

/**
 * NFR-UPLOAD-SPOOFING at the storage boundary: "zero non-image files are
 * ever written to the uploads volume, regardless of the declared
 * Content-Type". Non-image files with their real magic bytes, posing as
 * {@code fake.jpg}/{@code image/jpeg}, are refused with the unsupported-format
 * message and write nothing. Image polyglots — a real JPEG/PNG with a script
 * or an archive appended or embedded in its metadata — are accepted, but
 * only as a re-encoded WebP pair that carries none of the payload. Real
 * temp directory, real conversion ({@link PhotoStorageServiceTest} style).
 */
class PhotoUploadSpoofingTest {

    private static final String UNSUPPORTED =
            "Unsupported image format. Please upload JPEG, PNG, WebP, GIF, TIFF or BMP.";

    private static final byte[] MARKER = SpoofedUploads.MARKER.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SCRIPT_TAG = "<script".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_LOCAL_HEADER = {'P', 'K', 3, 4};

    @TempDir
    Path root;

    private PhotoStorageService service() {
        PhotoStorageProperties properties = TestPhotoStorage.properties(root);
        return new PhotoStorageService(
                properties,
                new PhotoUrlResolver(),
                new PhotoImageProcessor(properties),
                new PhotoFileDeleter(properties));
    }

    private static MockMultipartFile fakeJpeg(byte[] bytes) {
        return new MockMultipartFile("photo", "fake.jpg", "image/jpeg", bytes);
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    static Stream<Arguments> nonImages() {
        return Stream.of(
                Arguments.of(Named.of("Windows executable (MZ/PE)", SpoofedUploads.windowsExecutable())),
                Arguments.of(Named.of("Linux executable (ELF)", SpoofedUploads.linuxExecutable())),
                Arguments.of(Named.of("SVG with a script", SpoofedUploads.svgWithScript())),
                Arguments.of(Named.of("HTML with a script", SpoofedUploads.htmlWithScript())),
                Arguments.of(Named.of("PDF", SpoofedUploads.pdf())),
                Arguments.of(Named.of("ZIP archive", SpoofedUploads.zip())),
                Arguments.of(Named.of("shell script", SpoofedUploads.shellScript())));
    }

    @ParameterizedTest
    @MethodSource("nonImages")
    void nonImagePosingAsAJpeg_isRejectedAsUnsupported_andNothingIsWritten(byte[] bytes) throws Exception {
        assertThatThrownBy(() -> service().store(fakeJpeg(bytes)))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
        assertThat(storedFiles()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("nonImages")
    void nonImagePosingAsAPng_isRejectedTheSameWay(byte[] bytes) throws Exception {
        MockMultipartFile fakePng = new MockMultipartFile("photo", "fake.png", "image/png", bytes);

        assertThatThrownBy(() -> service().store(fakePng))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
        assertThat(storedFiles()).isEmpty();
    }

    static Stream<Arguments> imagePolyglots() {
        byte[] jpeg = TestImages.jpeg(TestImages.quadrants(64, 48));
        byte[] png = TestImages.png(TestImages.quadrants(64, 48));
        return Stream.of(
                Arguments.of(Named.of("JPEG + appended script",
                        SpoofedUploads.appended(jpeg, SpoofedUploads.SCRIPT.getBytes(StandardCharsets.US_ASCII)))),
                Arguments.of(Named.of("JPEG + appended ZIP", SpoofedUploads.appended(jpeg, SpoofedUploads.zip()))),
                Arguments.of(Named.of("JPEG + appended executable",
                        SpoofedUploads.appended(jpeg, SpoofedUploads.windowsExecutable()))),
                Arguments.of(Named.of("JPEG with a script comment segment",
                        SpoofedUploads.jpegWithScriptComment(jpeg))),
                Arguments.of(Named.of("PNG + appended script",
                        SpoofedUploads.appended(png, SpoofedUploads.SCRIPT.getBytes(StandardCharsets.US_ASCII)))),
                Arguments.of(Named.of("PNG with a script text chunk", SpoofedUploads.pngWithScriptText(png))));
    }

    @ParameterizedTest
    @MethodSource("imagePolyglots")
    void imageCarryingAPayload_isStoredAsAWebpPairWithoutThePayload(byte[] polyglot) throws Exception {
        StoredPhoto stored = service().store(fakeJpeg(polyglot));

        assertThat(storedFiles()).hasSize(2);
        for (String name : List.of(stored.filePath(), stored.thumbnailPath())) {
            byte[] written = Files.readAllBytes(root.resolve(name));
            assertThat(TestImages.isWebp(written)).as(name + " is a WebP").isTrue();
            assertThat(SpoofedUploads.contains(written, MARKER)).as(name + " carries the payload marker").isFalse();
            assertThat(SpoofedUploads.contains(written, SCRIPT_TAG)).as(name + " carries a script tag").isFalse();
            assertThat(SpoofedUploads.contains(written, ZIP_LOCAL_HEADER)).as(name + " carries a ZIP").isFalse();
        }
        assertThat(stored.width()).isEqualTo(64);
        assertThat(stored.height()).isEqualTo(48);
    }

    @ParameterizedTest
    @MethodSource("imagePolyglots")
    void processor_imageCarryingAPayload_returnsWebpBytesWithoutThePayload(byte[] polyglot) {
        PhotoImageProcessor processor = new PhotoImageProcessor(TestPhotoStorage.properties(root));

        ProcessedPhoto processed = processor.process(polyglot);

        for (byte[] webp : List.of(processed.fullWebp(), processed.thumbWebp())) {
            assertThat(TestImages.isWebp(webp)).isTrue();
            assertThat(SpoofedUploads.contains(webp, MARKER)).isFalse();
        }
    }
}
