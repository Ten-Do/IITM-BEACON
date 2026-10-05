package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestImages.TiffPage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Black-box tests of the upload normalisation pipeline: any readable image
 * in, EXIF-oriented, sRGB, metadata-free lossy WebP out (full size and
 * thumbnail). Every fixture is synthesised by {@link TestImages}.
 */
class PhotoImageProcessorTest {

    private static final String UNSUPPORTED =
            "Unsupported image format. Please upload JPEG, PNG, WebP, GIF, TIFF or BMP.";

    private static final int DEFAULT_FULL_EDGE = 2560;
    private static final int DEFAULT_THUMB_EDGE = 640;
    private static final long DEFAULT_MAX_PIXELS = 250_000_000L;

    private static PhotoStorageProperties properties(
            long maxPixels, int fullEdge, int thumbEdge, int quality, int thumbQuality) {
        return new PhotoStorageProperties(
                "/unused",
                50,
                5,
                20_971_520L,
                maxPixels,
                fullEdge,
                thumbEdge,
                quality,
                thumbQuality,
                new PhotoStorageProperties.Backfill(false));
    }

    private static PhotoImageProcessor processor() {
        return new PhotoImageProcessor(properties(DEFAULT_MAX_PIXELS, DEFAULT_FULL_EDGE, DEFAULT_THUMB_EDGE, 82, 75));
    }

    private static PhotoImageProcessor processorWithMaxPixels(long maxPixels) {
        return new PhotoImageProcessor(properties(maxPixels, DEFAULT_FULL_EDGE, DEFAULT_THUMB_EDGE, 82, 75));
    }

    // -- accepted formats --

    static Stream<Arguments> quadrantImagesInEveryAcceptedFormat() {
        BufferedImage source = TestImages.quadrants(80, 40);
        return Stream.of(
                Arguments.of(Named.of("JPEG", TestImages.jpeg(source))),
                Arguments.of(Named.of("PNG", TestImages.png(source))),
                Arguments.of(Named.of("GIF", TestImages.encode(source, "gif"))),
                Arguments.of(Named.of("BMP", TestImages.encode(source, "bmp"))),
                Arguments.of(Named.of("TIFF", TestImages.encode(source, "tiff"))),
                Arguments.of(Named.of("WebP", TestImages.webpLossless(source))));
    }

    @ParameterizedTest
    @MethodSource("quadrantImagesInEveryAcceptedFormat")
    void acceptedFormat_becomesWebpWithTheSamePixelsAndSize(byte[] input) {
        ProcessedPhoto result = processor().process(input);

        assertThat(TestImages.isWebp(result.fullWebp())).isTrue();
        assertThat(TestImages.isWebp(result.thumbWebp())).isTrue();
        BufferedImage full = TestImages.decode(result.fullWebp());
        assertThat(full.getWidth()).isEqualTo(80);
        assertThat(full.getHeight()).isEqualTo(40);
        assertThat(result.width()).isEqualTo(80);
        assertThat(result.height()).isEqualTo(40);
        assertThat(quadrantColours(full))
                .containsExactly(TestImages.RED, TestImages.GREEN, TestImages.BLUE, TestImages.WHITE);
    }

    @Test
    void pngWithAlpha_keepsTransparency() {
        ProcessedPhoto result = processor().process(TestImages.png(TestImages.halfTransparent(60, 40)));

        BufferedImage full = TestImages.decode(result.fullWebp());
        assertThat(full.getColorModel().hasAlpha()).isTrue();
        assertThat(alpha(full.getRGB(10, 20))).isEqualTo(255);
        assertThat(alpha(full.getRGB(50, 20))).isZero();
        BufferedImage thumb = TestImages.decode(result.thumbWebp());
        assertThat(alpha(thumb.getRGB(50, 20))).isZero();
    }

    @Test
    void gifWithTransparentIndex_keepsTransparency() {
        ProcessedPhoto result =
                processor().process(TestImages.encode(TestImages.halfTransparent(60, 40), "gif"));

        BufferedImage full = TestImages.decode(result.fullWebp());
        assertThat(alpha(full.getRGB(10, 20))).isEqualTo(255);
        assertThat(alpha(full.getRGB(50, 20))).isZero();
    }

    @Test
    void opaqueImage_hasNoTransparentPixels() {
        ProcessedPhoto result = processor().process(TestImages.jpeg(TestImages.solid(40, 30, TestImages.BLUE)));

        BufferedImage full = TestImages.decode(result.fullWebp());
        assertThat(alpha(full.getRGB(0, 0))).isEqualTo(255);
        assertThat(alpha(full.getRGB(39, 29))).isEqualTo(255);
    }

    @Test
    void sixteenBitRgbPng_isConvertedTo8BitWithTheSameColour() {
        BufferedImage sixteenBit = sixteenBitRgb(40, 30, 180, 120, 100);

        ProcessedPhoto result = processor().process(TestImages.png(sixteenBit));

        assertColourNear(TestImages.decode(result.fullWebp()).getRGB(20, 15), new Color(180, 120, 100), 4);
    }

    @ParameterizedTest
    @CsvSource({"TYPE_BYTE_GRAY", "TYPE_USHORT_GRAY"})
    void grayscalePng_keepsItsGrayLevel_insteadOfAGammaShift(String type) {
        BufferedImage gray = new BufferedImage(
                40, 30, "TYPE_BYTE_GRAY".equals(type) ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_USHORT_GRAY);
        int level = "TYPE_BYTE_GRAY".equals(type) ? 128 : 128 * 257;
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 40; x++) {
                gray.getRaster().setSample(x, y, 0, level);
            }
        }

        ProcessedPhoto result = processor().process(TestImages.png(gray));

        assertColourNear(TestImages.decode(result.fullWebp()).getRGB(20, 15), new Color(128, 128, 128), 4);
    }

    @Test
    void cmykJpeg_isConvertedToRgb() {
        ProcessedPhoto result = processor().process(TestImages.cmykJpegCyanLeftWhiteRight(60, 40));

        BufferedImage full = TestImages.decode(result.fullWebp());
        Color cyanSide = new Color(full.getRGB(10, 20));
        Color paperSide = new Color(full.getRGB(50, 20));
        assertThat(cyanSide.getRed()).isLessThan(80);
        assertThat(cyanSide.getGreen()).isGreaterThan(150);
        assertThat(cyanSide.getBlue()).isGreaterThan(150);
        assertThat(paperSide.getRed()).isGreaterThan(220);
        assertThat(paperSide.getGreen()).isGreaterThan(220);
        assertThat(paperSide.getBlue()).isGreaterThan(220);
    }

    // -- embedded Display P3 profile (iPhone photos and screenshots) --

    /**
     * (180,120,100) in Display P3 is roughly (190,117,96) in sRGB; simply
     * dropping the profile would keep (180,120,100) and wash the colours out.
     */
    private static final Color P3_SAMPLE = new Color(180, 120, 100);

    private static final Color P3_SAMPLE_IN_SRGB = new Color(190, 117, 96);

    @Test
    void jpegWithEmbeddedDisplayP3Profile_isConvertedToSrgb() {
        byte[] input = TestImages.jpegWithIccProfile(
                TestImages.solid(40, 30, P3_SAMPLE), TestImages.displayP3Profile());

        ProcessedPhoto result = processor().process(input);

        assertConvertedFromP3(TestImages.decode(result.fullWebp()).getRGB(20, 15));
    }

    @Test
    void pngWithEmbeddedDisplayP3Profile_isConvertedToSrgb() {
        byte[] input = TestImages.pngWithIccProfile(
                TestImages.png(TestImages.solid(40, 30, P3_SAMPLE)), TestImages.displayP3Profile());

        ProcessedPhoto result = processor().process(input);

        assertConvertedFromP3(TestImages.decode(result.fullWebp()).getRGB(20, 15));
    }

    @Test
    void pngWithAlphaAndEmbeddedDisplayP3Profile_isConvertedAndKeepsTransparency() {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 40; x++) {
                image.setRGB(x, y, x < 20 ? P3_SAMPLE.getRGB() : 0x00000000);
            }
        }
        byte[] input = TestImages.pngWithIccProfile(TestImages.png(image), TestImages.displayP3Profile());

        ProcessedPhoto result = processor().process(input);

        BufferedImage full = TestImages.decode(result.fullWebp());
        assertConvertedFromP3(full.getRGB(8, 15));
        assertThat(alpha(full.getRGB(35, 15))).isZero();
    }

    @Test
    void pngWithCorruptEmbeddedProfile_isStillAccepted() {
        byte[] png = TestImages.png(TestImages.solid(40, 30, P3_SAMPLE));
        java.awt.color.ICC_Profile valid = TestImages.displayP3Profile();
        byte[] input = TestImages.pngWithIccProfile(png, valid);
        // Corrupt the compressed profile bytes inside the iCCP chunk.
        int iccp = indexOf(input, "iCCP".getBytes(StandardCharsets.US_ASCII));
        for (int i = iccp + 20; i < iccp + 60; i++) {
            input[i] = (byte) 0x5A;
        }

        ProcessedPhoto result = processor().process(input);

        assertThat(TestImages.isWebp(result.fullWebp())).isTrue();
    }

    // -- multi-image containers (multi-page TIFF, DNG-as-TIFF) --

    @Test
    void multiPageTiff_theLargestPageWins() {
        byte[] input = TestImages.tiff(
                List.of(TiffPage.of(20, 10, TestImages.RED), TiffPage.of(60, 40, TestImages.GREEN)));

        ProcessedPhoto result = processor().process(input);

        assertThat(result.width()).isEqualTo(60);
        assertThat(result.height()).isEqualTo(40);
        assertColourNear(TestImages.decode(result.fullWebp()).getRGB(30, 20), TestImages.GREEN, 12);
    }

    @Test
    void multiPageTiff_largestPageFirst_stillWins() {
        byte[] input = TestImages.tiff(
                List.of(TiffPage.of(60, 40, TestImages.GREEN), TiffPage.of(20, 10, TestImages.RED)));

        ProcessedPhoto result = processor().process(input);

        assertThat(result.width()).isEqualTo(60);
        assertColourNear(TestImages.decode(result.fullWebp()).getRGB(30, 20), TestImages.GREEN, 12);
    }

    @Test
    void multiPageTiff_equallyLargePages_theFirstOneWins() {
        byte[] input = TestImages.tiff(
                List.of(TiffPage.of(30, 20, TestImages.RED), TiffPage.of(30, 20, TestImages.GREEN)));

        ProcessedPhoto result = processor().process(input);

        assertColourNear(TestImages.decode(result.fullWebp()).getRGB(15, 10), TestImages.RED, 12);
    }

    @Test
    void multiPageTiff_largestPageUndecodable_fallsBackToTheNextLargest() {
        byte[] input = TestImages.tiff(List.of(
                TiffPage.of(20, 10, TestImages.RED),
                TiffPage.undecodable(60, 40),
                TiffPage.of(30, 20, TestImages.BLUE)));

        ProcessedPhoto result = processor().process(input);

        assertThat(result.width()).isEqualTo(30);
        assertThat(result.height()).isEqualTo(20);
        assertColourNear(TestImages.decode(result.fullWebp()).getRGB(15, 10), TestImages.BLUE, 12);
    }

    @Test
    void tiffWhereNoPageDecodes_isRejectedAsUnsupported() {
        byte[] input = TestImages.tiff(List.of(TiffPage.undecodable(60, 40), TiffPage.undecodable(20, 10)));

        assertThatThrownBy(() -> processor().process(input))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
    }

    // -- EXIF orientation --

    /** Stored 80x40 quadrants (R G / B W); expected displayed size and TL, TR, BL, BR colours. */
    static Stream<Arguments> exifOrientations() {
        Color r = TestImages.RED;
        Color g = TestImages.GREEN;
        Color b = TestImages.BLUE;
        Color w = TestImages.WHITE;
        return Stream.of(
                Arguments.of(1, 80, 40, List.of(r, g, b, w)),
                Arguments.of(2, 80, 40, List.of(g, r, w, b)),
                Arguments.of(3, 80, 40, List.of(w, b, g, r)),
                Arguments.of(4, 80, 40, List.of(b, w, r, g)),
                Arguments.of(5, 40, 80, List.of(r, b, g, w)),
                Arguments.of(6, 40, 80, List.of(b, r, w, g)),
                Arguments.of(7, 40, 80, List.of(w, g, b, r)),
                Arguments.of(8, 40, 80, List.of(g, w, r, b)));
    }

    @ParameterizedTest(name = "orientation {0}")
    @MethodSource("exifOrientations")
    void exifOrientation_isAppliedToThePixels(int orientation, int width, int height, List<Color> quadrants) {
        byte[] input = TestImages.jpegWithExif(TestImages.quadrants(80, 40), orientation, false);

        ProcessedPhoto result = processor().process(input);

        BufferedImage full = TestImages.decode(result.fullWebp());
        assertThat(full.getWidth()).isEqualTo(width);
        assertThat(full.getHeight()).isEqualTo(height);
        assertThat(result.width()).isEqualTo(width);
        assertThat(result.height()).isEqualTo(height);
        assertThat(quadrantColours(full)).containsExactlyElementsOf(quadrants);
        assertThat(quadrantColours(TestImages.decode(result.thumbWebp()))).containsExactlyElementsOf(quadrants);
    }

    @ParameterizedTest
    @CsvSource({"0", "9", "65535"})
    void outOfRangeExifOrientation_isIgnored(int orientation) {
        byte[] input = TestImages.jpegWithExif(TestImages.quadrants(80, 40), orientation, false);

        ProcessedPhoto result = processor().process(input);

        assertThat(result.width()).isEqualTo(80);
        assertThat(quadrantColours(TestImages.decode(result.fullWebp())))
                .containsExactly(TestImages.RED, TestImages.GREEN, TestImages.BLUE, TestImages.WHITE);
    }

    @Test
    void rotatedLargePhoto_isRotatedAndDownscaledToThePortraitBox() {
        byte[] input = TestImages.jpegWithExif(TestImages.quadrants(3000, 1000), 6, false);

        ProcessedPhoto result = processor().process(input);

        assertThat(result.height()).isEqualTo(DEFAULT_FULL_EDGE);
        assertThat(result.width()).isCloseTo(853, within(1));
        BufferedImage thumb = TestImages.decode(result.thumbWebp());
        assertThat(thumb.getHeight()).isEqualTo(DEFAULT_THUMB_EDGE);
        assertThat(thumb.getWidth()).isCloseTo(213, within(1));
    }

    // -- metadata stripping --

    @Test
    void exifAndGps_areNotCarriedOverIntoEitherOutput() throws Exception {
        byte[] input = TestImages.jpegWithExif(TestImages.quadrants(80, 40), 6, true);
        assertThat(metadataOf(input).getFirstDirectoryOfType(GpsDirectory.class))
                .as("fixture sanity: the input carries GPS")
                .isNotNull();

        ProcessedPhoto result = processor().process(input);

        for (byte[] output : List.of(result.fullWebp(), result.thumbWebp())) {
            Metadata metadata = metadataOf(output);
            assertThat(metadata.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
            assertThat(metadata.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
            List<String> directories = new ArrayList<>();
            for (Directory directory : metadata.getDirectories()) {
                directories.add(directory.getName());
            }
            assertThat(directories).containsOnly("WebP", "File Type");
        }
    }

    @Test
    void embeddedIccProfile_isNotCarriedOverIntoTheOutput() throws Exception {
        byte[] input = TestImages.jpegWithIccProfile(
                TestImages.solid(40, 30, P3_SAMPLE), TestImages.displayP3Profile());

        ProcessedPhoto result = processor().process(input);

        List<String> directories = new ArrayList<>();
        for (Directory directory : metadataOf(result.fullWebp()).getDirectories()) {
            directories.add(directory.getName());
        }
        assertThat(directories).containsOnly("WebP", "File Type");
    }

    // -- size limits: full <= 2560, thumb <= 640 on the long edge, never upscaled --

    @ParameterizedTest(name = "{0}x{1} -> full {2}x{3}, thumb {4}x{5}")
    @CsvSource({
        "2560, 40, 2560, 40, 640, 10",
        "2561, 40, 2560, 40, 640, 10",
        "40, 2561, 40, 2560, 10, 640",
        "640, 10, 640, 10, 640, 10",
        "641, 10, 641, 10, 640, 10",
        "300, 200, 300, 200, 300, 200",
        "1, 1, 1, 1, 1, 1",
        "3000, 1, 2560, 1, 640, 1",
        "3000, 2000, 2560, 1707, 640, 427"
    })
    void outputSizes_areCappedOnTheLongEdge_neverUpscaled_andKeepTheAspectRatio(
            int width, int height, int fullW, int fullH, int thumbW, int thumbH) {
        ProcessedPhoto result = processor().process(TestImages.png(TestImages.solid(width, height, TestImages.BLUE)));

        BufferedImage full = TestImages.decode(result.fullWebp());
        BufferedImage thumb = TestImages.decode(result.thumbWebp());
        assertThat(full.getWidth()).isCloseTo(fullW, within(1));
        assertThat(full.getHeight()).isCloseTo(fullH, within(1));
        assertThat(Math.max(full.getWidth(), full.getHeight())).isEqualTo(Math.max(fullW, fullH));
        assertThat(thumb.getWidth()).isCloseTo(thumbW, within(1));
        assertThat(thumb.getHeight()).isCloseTo(thumbH, within(1));
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(Math.max(thumbW, thumbH));
        assertThat(result.width()).isEqualTo(full.getWidth());
        assertThat(result.height()).isEqualTo(full.getHeight());
    }

    @Test
    void downscaledPhoto_keepsItsContent() {
        ProcessedPhoto result = processor().process(TestImages.jpeg(TestImages.quadrants(4000, 3000)));

        assertThat(result.width()).isEqualTo(2560);
        assertThat(result.height()).isEqualTo(1920);
        assertThat(quadrantColours(TestImages.decode(result.fullWebp())))
                .containsExactly(TestImages.RED, TestImages.GREEN, TestImages.BLUE, TestImages.WHITE);
        assertThat(quadrantColours(TestImages.decode(result.thumbWebp())))
                .containsExactly(TestImages.RED, TestImages.GREEN, TestImages.BLUE, TestImages.WHITE);
    }

    @Test
    void configuredEdges_areHonoured() {
        PhotoImageProcessor small = new PhotoImageProcessor(properties(DEFAULT_MAX_PIXELS, 100, 30, 82, 75));

        ProcessedPhoto result = small.process(TestImages.png(TestImages.solid(400, 200, TestImages.RED)));

        assertThat(result.width()).isEqualTo(100);
        assertThat(result.height()).isEqualTo(50);
        BufferedImage thumb = TestImages.decode(result.thumbWebp());
        assertThat(thumb.getWidth()).isEqualTo(30);
        assertThat(thumb.getHeight()).isEqualTo(15);
    }

    @Test
    void configuredFullQuality_drivesOnlyTheFullImage() {
        byte[] input = TestImages.png(TestImages.noise(400, 300, 7));
        PhotoImageProcessor low = new PhotoImageProcessor(properties(DEFAULT_MAX_PIXELS, 2560, 200, 20, 75));
        PhotoImageProcessor high = new PhotoImageProcessor(properties(DEFAULT_MAX_PIXELS, 2560, 200, 95, 75));

        ProcessedPhoto lowResult = low.process(input);
        ProcessedPhoto highResult = high.process(input);

        assertThat(highResult.fullWebp().length).isGreaterThan(lowResult.fullWebp().length);
        assertThat(highResult.thumbWebp()).isEqualTo(lowResult.thumbWebp());
    }

    @Test
    void configuredThumbQuality_drivesOnlyTheThumbnail() {
        byte[] input = TestImages.png(TestImages.noise(400, 300, 7));
        PhotoImageProcessor low = new PhotoImageProcessor(properties(DEFAULT_MAX_PIXELS, 2560, 200, 82, 20));
        PhotoImageProcessor high = new PhotoImageProcessor(properties(DEFAULT_MAX_PIXELS, 2560, 200, 82, 95));

        ProcessedPhoto lowResult = low.process(input);
        ProcessedPhoto highResult = high.process(input);

        assertThat(highResult.thumbWebp().length).isGreaterThan(lowResult.thumbWebp().length);
        assertThat(highResult.fullWebp()).isEqualTo(lowResult.fullWebp());
    }

    @Test
    void output_isLossyWebp() {
        ProcessedPhoto result = processor().process(TestImages.png(TestImages.noise(64, 64, 3)));

        // Lossy WebP carries a "VP8 " bitstream chunk (lossless would be "VP8L").
        assertThat(indexOf(result.fullWebp(), "VP8 ".getBytes(StandardCharsets.US_ASCII))).isPositive();
        assertThat(indexOf(result.fullWebp(), "VP8L".getBytes(StandardCharsets.US_ASCII))).isNegative();
    }

    // -- rejected input --

    static Stream<Arguments> unsupportedInputs() {
        byte[] jpeg = TestImages.jpeg(TestImages.solid(40, 30, TestImages.RED));
        byte[] jpegHeaderOnly = java.util.Arrays.copyOf(jpeg, 20);
        return Stream.of(
                Arguments.of(Named.of("empty", new byte[0])),
                Arguments.of(Named.of("one byte", new byte[] {(byte) 0xFF})),
                Arguments.of(Named.of("plain text", "not actually an image".getBytes(StandardCharsets.UTF_8))),
                Arguments.of(Named.of("HEIC", TestImages.isoBmffFile("heic"))),
                Arguments.of(Named.of("HEIF", TestImages.isoBmffFile("mif1"))),
                Arguments.of(Named.of("AVIF", TestImages.isoBmffFile("avif"))),
                Arguments.of(Named.of("JPEG cut off after its first bytes", jpegHeaderOnly)),
                Arguments.of(Named.of("PNG signature only", java.util.Arrays.copyOf(TestImages.png(
                        TestImages.solid(4, 4, TestImages.RED)), 8))));
    }

    @ParameterizedTest
    @MethodSource("unsupportedInputs")
    void unreadableInput_isRejectedWithTheUnsupportedFormatMessage(byte[] input) {
        assertThatThrownBy(() -> processor().process(input))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
    }

    // -- decompression bombs: dimensions are checked before decoding --

    @Test
    void imageDeclaringMoreThanMaxPixels_isRejectedBeforeDecoding() {
        // 20000 x 20000 = 400 MP; the file has no pixel data at all, so only
        // a header check can produce the "too large" message.
        byte[] bomb = TestImages.pngHeaderOnly(20_000, 20_000);

        assertThatThrownBy(() -> processor().process(bomb))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessageContaining("too large")
                .hasMessageContaining("250 megapixels");
    }

    @Test
    void imageDeclaringExactlyMaxPixels_passesTheDimensionCheck() {
        // 16000 x 15625 = 250 MP exactly: not "too large" — it only fails
        // afterwards because the fixture has no pixel data to decode.
        byte[] atLimit = TestImages.pngHeaderOnly(16_000, 15_625);

        assertThatThrownBy(() -> processor().process(atLimit))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessage(UNSUPPORTED);
    }

    @Test
    void imageOfExactlyMaxPixels_isAccepted_andOnePixelMore_isRejected() {
        PhotoImageProcessor limited = processorWithMaxPixels(50L * 40L);

        assertThat(limited.process(TestImages.png(TestImages.solid(50, 40, TestImages.RED))).width()).isEqualTo(50);
        assertThatThrownBy(() -> limited.process(TestImages.png(TestImages.solid(51, 40, TestImages.RED))))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void multiPageTiffWithOnePageAboveMaxPixels_isRejected() {
        PhotoImageProcessor limited = processorWithMaxPixels(2_000L);
        byte[] input = TestImages.tiff(
                List.of(TiffPage.of(10, 10, TestImages.RED), TiffPage.of(60, 40, TestImages.GREEN)));

        assertThatThrownBy(() -> limited.process(input))
                .isInstanceOf(SubmissionValidationException.class)
                .hasMessageContaining("too large");
    }

    // -- source subsampling: decode no larger than needed for the full target --

    @ParameterizedTest(name = "{0}x{1} for a {2}px target -> every {3}th pixel")
    @CsvSource({
        "1, 1, 2560, 1",
        "2560, 1920, 2560, 1",
        "5119, 100, 2560, 1",
        "5120, 100, 2560, 2",
        "100, 5120, 2560, 2",
        "8064, 6048, 2560, 3",
        "16320, 12240, 2560, 6"
    })
    void subsamplingFactor_keepsTheDecodedLongEdgeAtLeastTheTarget(int width, int height, int target, int expected) {
        assertThat(PhotoImageProcessor.subsamplingFor(width, height, target)).isEqualTo(expected);
    }

    // -- fail fast when the native WebP encoder is unusable --

    @Test
    void noWebpWriterRegistered_failsAtConstruction() {
        PhotoStorageProperties properties = properties(DEFAULT_MAX_PIXELS, 2560, 640, 82, 75);

        assertThatThrownBy(() -> new PhotoImageProcessor(properties, Optional::empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WebP");
    }

    @Test
    void webpWriterWhoseNativeLibraryCannotLoad_failsAtConstruction() throws Exception {
        ImageWriteParam realWebpParam =
                ImageIO.getImageWritersByMIMEType("image/webp").next().getDefaultWriteParam();
        ImageWriter broken = mock(ImageWriter.class);
        when(broken.getDefaultWriteParam()).thenReturn(realWebpParam);
        doThrow(new UnsatisfiedLinkError("libwebp-imageio.so: cannot open shared object file"))
                .when(broken)
                .write(any(), any(), any());
        PhotoStorageProperties properties = properties(DEFAULT_MAX_PIXELS, 2560, 640, 82, 75);

        assertThatThrownBy(() -> new PhotoImageProcessor(properties, () -> Optional.of(broken)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WebP")
                .hasCauseInstanceOf(UnsatisfiedLinkError.class);
    }

    // -- helpers --

    private static final List<Color> PALETTE =
            List.of(TestImages.RED, TestImages.GREEN, TestImages.BLUE, TestImages.WHITE);

    /** Nearest palette colour at the centre of each quadrant: TL, TR, BL, BR. */
    private static List<Color> quadrantColours(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return List.of(
                nearest(image.getRGB(w / 4, h / 4)),
                nearest(image.getRGB(3 * w / 4, h / 4)),
                nearest(image.getRGB(w / 4, 3 * h / 4)),
                nearest(image.getRGB(3 * w / 4, 3 * h / 4)));
    }

    private static Color nearest(int rgb) {
        Color actual = new Color(rgb);
        Color best = PALETTE.get(0);
        long bestDistance = Long.MAX_VALUE;
        for (Color candidate : PALETTE) {
            long dr = actual.getRed() - candidate.getRed();
            long dg = actual.getGreen() - candidate.getGreen();
            long db = actual.getBlue() - candidate.getBlue();
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static void assertColourNear(int rgb, Color expected, int tolerance) {
        Color actual = new Color(rgb);
        assertThat(actual.getRed()).as("red of " + actual).isCloseTo(expected.getRed(), within(tolerance));
        assertThat(actual.getGreen()).as("green of " + actual).isCloseTo(expected.getGreen(), within(tolerance));
        assertThat(actual.getBlue()).as("blue of " + actual).isCloseTo(expected.getBlue(), within(tolerance));
    }

    private static void assertConvertedFromP3(int rgb) {
        assertColourNear(rgb, P3_SAMPLE_IN_SRGB, 4);
        assertThat(new Color(rgb).getRed()).as("not the unconverted P3 value").isGreaterThanOrEqualTo(186);
    }

    private static BufferedImage sixteenBitRgb(int width, int height, int r, int g, int b) {
        java.awt.image.ColorModel model = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                false,
                false,
                java.awt.Transparency.OPAQUE,
                java.awt.image.DataBuffer.TYPE_USHORT);
        java.awt.image.WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        int[] pixel = {r * 257, g * 257, b * 257};
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setPixel(x, y, pixel);
            }
        }
        return new BufferedImage(model, raster, false, null);
    }

    private static Metadata metadataOf(byte[] bytes) throws Exception {
        return ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
