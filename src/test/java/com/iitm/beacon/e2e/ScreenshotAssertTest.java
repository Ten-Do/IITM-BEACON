package com.iitm.beacon.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * File-level behaviour of {@link ScreenshotAssert} — baseline lookup, update
 * mode and failure artifacts — driven with synthetic PNG bytes in temporary
 * directories, so no browser is needed. Plain unit test (not tagged
 * {@code e2e}), so it runs in the default {@code mvn test}.
 */
class ScreenshotAssertTest {

    private static final int GREY = 0xFF808080;
    private static final int RED = 0xFFFF0000;

    @TempDir
    private Path tmp;

    private Path baselineRoot;
    private Path outputRoot;

    /** Stand-in "test class" whose simple name becomes the screenshot folder. */
    private static final class SomeE2eTest {
    }

    @BeforeEach
    void roots() {
        baselineRoot = tmp.resolve("src/test/resources/e2e-screenshots");
        outputRoot = tmp.resolve("target/e2e-screenshots");
    }

    private ScreenshotAssert verifying() {
        return new ScreenshotAssert(baselineRoot, outputRoot, false);
    }

    private ScreenshotAssert updating() {
        return new ScreenshotAssert(baselineRoot, outputRoot, true);
    }

    private static byte[] png(int width, int height, int argb, int changedPixels) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        for (int i = 0; i < changedPixels; i++) {
            image.setRGB(i % width, i / width, RED);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] png(int width, int height, int argb) {
        return png(width, height, argb, 0);
    }

    private Path baseline(String name) {
        return baselineRoot.resolve("SomeE2eTest").resolve(name + ".png");
    }

    private Path actualArtifact(String name) {
        return outputRoot.resolve("SomeE2eTest").resolve(name + "-actual.png");
    }

    private Path diffArtifact(String name) {
        return outputRoot.resolve("SomeE2eTest").resolve(name + "-diff.png");
    }

    private void writeBaseline(String name, byte[] bytes) throws IOException {
        Files.createDirectories(baseline(name).getParent());
        Files.write(baseline(name), bytes);
    }

    private static BufferedImage decode(Path file) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(file)));
    }

    // -- baseline location --

    @Test
    void baselinePath_isTheClassSimpleNameFolderPlusNameDotPng() {
        assertThat(verifying().baselinePath(SomeE2eTest.class, "gallery-desktop"))
                .isEqualTo(baselineRoot.resolve("SomeE2eTest/gallery-desktop.png"));
    }

    // -- verify mode: matching --

    @Test
    void matchingBaseline_passes_andWritesNoFailureArtifacts() throws IOException {
        writeBaseline("page", png(20, 10, GREY));

        assertThatCode(() -> verifying().assertMatches(SomeE2eTest.class, "page", png(20, 10, GREY)))
                .doesNotThrowAnyException();

        assertThat(actualArtifact("page")).doesNotExist();
        assertThat(diffArtifact("page")).doesNotExist();
    }

    @Test
    void matchingBaseline_removesStaleArtifactsLeftByAnEarlierFailedRun() throws IOException {
        writeBaseline("page", png(20, 10, GREY));
        Files.createDirectories(actualArtifact("page").getParent());
        Files.write(actualArtifact("page"), png(1, 1, RED));
        Files.write(diffArtifact("page"), png(1, 1, RED));

        verifying().assertMatches(SomeE2eTest.class, "page", png(20, 10, GREY));

        assertThat(actualArtifact("page")).doesNotExist();
        assertThat(diffArtifact("page")).doesNotExist();
    }

    @Test
    void differenceWithinTheDefaultTolerance_passes() throws IOException {
        writeBaseline("page", png(100, 100, GREY));
        byte[] slightlyOff = png(100, 100, 0xFF838383); // every pixel off by 3 per channel

        assertThatCode(() -> verifying().assertMatches(SomeE2eTest.class, "page", slightlyOff))
                .doesNotThrowAnyException();
    }

    // -- verify mode: mismatches --

    @Test
    void differenceAboveTheRatio_failsWithRatioAndArtifactPaths_andWritesActualAndDiff() throws IOException {
        writeBaseline("page", png(10, 10, GREY));
        byte[] actual = png(10, 10, GREY, 5); // 5 of 100 pixels red

        assertThatThrownBy(() -> verifying().assertMatches(
                        SomeE2eTest.class, "page", actual, new ScreenshotTolerance(0, 0.01)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("5.00%")
                .hasMessageContaining("5 of 100")
                .hasMessageContaining(baseline("page").toString())
                .hasMessageContaining(actualArtifact("page").toString())
                .hasMessageContaining(diffArtifact("page").toString());

        assertThat(Files.readAllBytes(actualArtifact("page"))).isEqualTo(actual);
        BufferedImage diff = decode(diffArtifact("page"));
        assertThat(diff.getWidth()).isEqualTo(10);
        assertThat(diff.getRGB(0, 0)).isEqualTo(RED);
    }

    @Test
    void failedComparison_leavesTheBaselineUntouched() throws IOException {
        byte[] original = png(10, 10, GREY);
        writeBaseline("page", original);

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "page", png(10, 10, GREY, 50)))
                .isInstanceOf(AssertionError.class);

        assertThat(Files.readAllBytes(baseline("page"))).isEqualTo(original);
    }

    @Test
    void perCallTolerance_overridesTheDefault_inBothDirections() throws IOException {
        writeBaseline("page", png(10, 10, GREY));
        byte[] tenPercentRed = png(10, 10, GREY, 10);
        byte[] oneUnitOff = png(10, 10, 0xFF818080);

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "page", tenPercentRed))
                .isInstanceOf(AssertionError.class);
        assertThatCode(() -> verifying().assertMatches(
                        SomeE2eTest.class, "page", tenPercentRed, new ScreenshotTolerance(0, 0.1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verifying().assertMatches(
                        SomeE2eTest.class, "page", oneUnitOff, ScreenshotTolerance.EXACT))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void differentWidth_failsWithBothSizes_andWritesActualButNoDiff() throws IOException {
        writeBaseline("page", png(20, 10, GREY));
        byte[] actual = png(21, 10, GREY);

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "page", actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("20x10")
                .hasMessageContaining("21x10")
                .hasMessageContaining(actualArtifact("page").toString());

        assertThat(Files.readAllBytes(actualArtifact("page"))).isEqualTo(actual);
        assertThat(diffArtifact("page")).doesNotExist();
    }

    @Test
    void differentHeightOnly_fails_evenWithTheLoosestTolerance() throws IOException {
        writeBaseline("page", png(20, 10, GREY));

        assertThatThrownBy(() -> verifying().assertMatches(
                        SomeE2eTest.class, "page", png(20, 11, GREY), new ScreenshotTolerance(255, 1.0)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("20x11");
    }

    @Test
    void sizeMismatchFailure_removesAStaleDiffLeftByAnEarlierRun() throws IOException {
        writeBaseline("page", png(10, 10, GREY));
        Files.createDirectories(diffArtifact("page").getParent());
        Files.write(diffArtifact("page"), png(1, 1, GREY));

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "page", png(11, 10, GREY)))
                .isInstanceOf(AssertionError.class);

        assertThat(diffArtifact("page")).doesNotExist();
    }

    // -- verify mode: missing / unreadable baseline --

    @Test
    void missingBaseline_failsWithTheUpdateHint_andDoesNotCreateIt() {
        byte[] actual = png(10, 10, GREY);

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "new-page", actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No baseline")
                .hasMessageContaining(baseline("new-page").toString())
                .hasMessageContaining("make e2e-update-screenshots");

        assertThat(baseline("new-page")).doesNotExist();
    }

    @Test
    void missingBaseline_stillWritesTheActualScreenshotForInspection() throws IOException {
        byte[] actual = png(10, 10, GREY);

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "new-page", actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(actualArtifact("new-page").toString());

        assertThat(Files.readAllBytes(actualArtifact("new-page"))).isEqualTo(actual);
    }

    @Test
    void undecodableBaseline_failsWithTheUpdateHint_insteadOfCrashing() throws IOException {
        writeBaseline("page", "not a png".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> verifying().assertMatches(SomeE2eTest.class, "page", png(10, 10, GREY)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cannot be decoded")
                .hasMessageContaining("make e2e-update-screenshots");
    }

    // -- update mode --

    @Test
    void updateMode_missingBaseline_createsFoldersAndWritesTheBaseline_andPasses() throws IOException {
        byte[] actual = png(10, 10, GREY);

        assertThatCode(() -> updating().assertMatches(SomeE2eTest.class, "new-page", actual))
                .doesNotThrowAnyException();

        assertThat(Files.readAllBytes(baseline("new-page"))).isEqualTo(actual);
        assertThat(actualArtifact("new-page")).doesNotExist();
    }

    @Test
    void updateMode_mismatch_overwritesTheBaseline_andPasses_withoutFailureArtifacts() throws IOException {
        writeBaseline("page", png(10, 10, GREY));
        byte[] actual = png(10, 10, GREY, 50);

        assertThatCode(() -> updating().assertMatches(SomeE2eTest.class, "page", actual))
                .doesNotThrowAnyException();

        assertThat(Files.readAllBytes(baseline("page"))).isEqualTo(actual);
        assertThat(actualArtifact("page")).doesNotExist();
        assertThat(diffArtifact("page")).doesNotExist();
    }

    @Test
    void updateMode_sizeMismatch_overwritesTheBaseline() throws IOException {
        writeBaseline("page", png(10, 10, GREY));
        byte[] actual = png(12, 9, GREY);

        updating().assertMatches(SomeE2eTest.class, "page", actual);

        assertThat(Files.readAllBytes(baseline("page"))).isEqualTo(actual);
    }

    @Test
    void updateMode_undecodableBaseline_isReplaced() throws IOException {
        writeBaseline("page", new byte[0]);
        byte[] actual = png(10, 10, GREY);

        updating().assertMatches(SomeE2eTest.class, "page", actual);

        assertThat(Files.readAllBytes(baseline("page"))).isEqualTo(actual);
    }

    @Test
    void updateMode_differenceWithinTolerance_keepsTheExistingBaselineBytes() throws IOException {
        // Mirrors Playwright's "update only what changed": sub-tolerance
        // rendering noise must not churn committed PNGs.
        byte[] original = png(100, 100, GREY);
        writeBaseline("page", original);

        updating().assertMatches(SomeE2eTest.class, "page", png(100, 100, 0xFF818181));

        assertThat(Files.readAllBytes(baseline("page"))).isEqualTo(original);
    }

    // -- invalid input --

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualThatIsNotAPng_isRejected_andNothingIsWritten(boolean updateMode) {
        ScreenshotAssert screenshots = new ScreenshotAssert(baselineRoot, outputRoot, updateMode);

        assertThatIllegalArgumentException().isThrownBy(() -> screenshots.assertMatches(
                SomeE2eTest.class, "page", "garbage".getBytes(StandardCharsets.UTF_8)));
        assertThatIllegalArgumentException().isThrownBy(() -> screenshots.assertMatches(
                SomeE2eTest.class, "page", new byte[0]));

        assertThat(baseline("page")).doesNotExist();
        assertThat(actualArtifact("page")).doesNotExist();
    }

    @Test
    void nullArguments_areRejected() {
        byte[] actual = png(1, 1, GREY);

        assertThatNullPointerException().isThrownBy(() -> verifying().assertMatches(null, "page", actual));
        assertThatNullPointerException().isThrownBy(
                () -> verifying().assertMatches(SomeE2eTest.class, "page", (byte[]) null));
        assertThatNullPointerException().isThrownBy(
                () -> verifying().assertMatches(SomeE2eTest.class, "page", actual, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "../escape", "a/b", "a\\b", "..", ".hidden", "with space", "-leading-dash"})
    void unsafeOrBlankNames_areRejected_beforeTouchingTheFileSystem(String name) {
        byte[] actual = png(1, 1, GREY);

        assertThatIllegalArgumentException().isThrownBy(
                () -> updating().assertMatches(SomeE2eTest.class, name, actual));

        assertThat(baselineRoot).doesNotExist();
        assertThat(outputRoot).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "gallery-desktop", "Gallery_Mobile.v2", "step1-login-code"})
    void safeNames_areAccepted(String name) {
        assertThatCode(() -> updating().assertMatches(SomeE2eTest.class, name, png(1, 1, GREY)))
                .doesNotThrowAnyException();
    }

    // -- update-mode switch parsing --

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    void updateModeSwitch_trueIgnoringCase_enablesUpdates(String value) {
        assertThat(ScreenshotAssert.isUpdateMode(value)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "yes", "1", "on"})
    void updateModeSwitch_anythingElse_keepsVerifyMode(String value) {
        assertThat(ScreenshotAssert.isUpdateMode(value)).isFalse();
    }
}
