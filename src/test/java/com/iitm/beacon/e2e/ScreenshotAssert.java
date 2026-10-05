package com.iitm.beacon.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotCaret;
import com.microsoft.playwright.options.ScreenshotScale;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screenshot-baseline assertion for the browser end-to-end tests — our own
 * stand-in for Playwright Test's {@code toHaveScreenshot()}, which the Java
 * client does not have.
 *
 * <p>A screenshot named {@code name} taken in test class {@code C} is compared
 * against {@code <baselineRoot>/<C simple name>/<name>.png} using a {@link
 * ScreenshotTolerance}. On a mismatch the actual screenshot (and, for
 * same-size images, a diff with the differing pixels in red) is written to
 * {@code <outputRoot>/<C simple name>/<name>-actual.png} / {@code
 * <name>-diff.png} and the assertion fails with the diff ratio and those
 * paths. A missing or undecodable baseline also fails.
 *
 * <p>In update mode ({@code -De2e.updateScreenshots=true}, i.e. {@code make
 * e2e-update-screenshots}) every baseline that is missing, undecodable or no
 * longer within tolerance is (re)written from the actual screenshot and the
 * assertion passes; baselines still within tolerance are left byte-for-byte
 * untouched, so rendering noise does not churn committed PNGs.
 */
public final class ScreenshotAssert {

    /** System property that switches on update mode. */
    public static final String UPDATE_PROPERTY = "e2e.updateScreenshots";

    private static final Logger log = LoggerFactory.getLogger(ScreenshotAssert.class);

    private static final String UPDATE_HINT = "run `make e2e-update-screenshots`";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path baselineRoot;
    private final Path outputRoot;
    private final boolean updateMode;

    public ScreenshotAssert(Path baselineRoot, Path outputRoot, boolean updateMode) {
        this.baselineRoot = Objects.requireNonNull(baselineRoot, "baselineRoot");
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot");
        this.updateMode = updateMode;
    }

    /**
     * Baselines in {@code src/test/resources/e2e-screenshots}, failure
     * artifacts in {@code target/e2e-screenshots} (both relative to the
     * module directory, which is surefire's working directory), update mode
     * from the {@value #UPDATE_PROPERTY} system property.
     */
    public static ScreenshotAssert fromSystemProperties() {
        return new ScreenshotAssert(
                Path.of("src", "test", "resources", "e2e-screenshots"),
                Path.of("target", "e2e-screenshots"),
                isUpdateMode(System.getProperty(UPDATE_PROPERTY)));
    }

    /** {@code "true"} in any letter case enables update mode; anything else (or unset) does not. */
    static boolean isUpdateMode(String propertyValue) {
        return Boolean.parseBoolean(propertyValue);
    }

    /**
     * Where the baseline for {@code name} in {@code testClass} lives.
     *
     * @throws IllegalArgumentException if {@code name} is blank or not a
     *     plain file-name stem (letters, digits, {@code . _ -}, starting with a
     *     letter or digit)
     */
    public Path baselinePath(Class<?> testClass, String name) {
        Objects.requireNonNull(testClass, "testClass");
        requireSafeName(name);
        return baselineRoot.resolve(testClass.getSimpleName()).resolve(name + ".png");
    }

    /** Viewport (or full-page) PNG with animations frozen, caret hidden and CSS-pixel scale. */
    public static byte[] capture(Page page, boolean fullPage) {
        return page.screenshot(new Page.ScreenshotOptions()
                .setFullPage(fullPage)
                .setAnimations(ScreenshotAnimations.DISABLED)
                .setCaret(ScreenshotCaret.HIDE)
                .setScale(ScreenshotScale.CSS));
    }

    /** PNG of one element, with the same stabilising options as {@link #capture(Page, boolean)}. */
    public static byte[] capture(Locator locator) {
        return locator.screenshot(new Locator.ScreenshotOptions()
                .setAnimations(ScreenshotAnimations.DISABLED)
                .setCaret(ScreenshotCaret.HIDE)
                .setScale(ScreenshotScale.CSS));
    }

    public void assertMatches(Class<?> testClass, String name, byte[] actualPng) {
        assertMatches(testClass, name, actualPng, ScreenshotTolerance.DEFAULT);
    }

    /**
     * Compares {@code actualPng} with the baseline — see the class comment
     * for failure artifacts and update mode.
     *
     * @throws IllegalArgumentException if {@code actualPng} is not a
     *     decodable image (nothing is written in that case, not even in
     *     update mode) or {@code name} is unsafe
     * @throws AssertionError if the screenshot does not match (verify mode only)
     */
    public void assertMatches(Class<?> testClass, String name, byte[] actualPng, ScreenshotTolerance tolerance) {
        Path baseline = baselinePath(testClass, name);
        Objects.requireNonNull(actualPng, "actualPng");
        Objects.requireNonNull(tolerance, "tolerance");
        BufferedImage actual = decode(actualPng).orElseThrow(
                () -> new IllegalArgumentException("Actual screenshot '" + name + "' is not a decodable image"));

        Path artifactDir = outputRoot.resolve(testClass.getSimpleName());
        Path actualArtifact = artifactDir.resolve(name + "-actual.png");
        Path diffArtifact = artifactDir.resolve(name + "-diff.png");
        deleteIfExists(actualArtifact);
        deleteIfExists(diffArtifact);

        if (!Files.exists(baseline)) {
            failOrUpdate(baseline, actualPng, actualArtifact, "No baseline screenshot at " + baseline + ".");
            return;
        }
        Optional<BufferedImage> expected = decode(readAllBytes(baseline));
        if (expected.isEmpty()) {
            failOrUpdate(baseline, actualPng, actualArtifact,
                    "Baseline screenshot " + baseline + " cannot be decoded as an image.");
            return;
        }

        ImageComparison comparison = ImageComparison.compare(expected.get(), actual, tolerance);
        if (comparison.matches()) {
            return;
        }
        String problem = switch (comparison) {
            case ImageComparison.SizeMismatch size -> String.format(Locale.ROOT,
                    "Screenshot '%s' is %dx%d but its baseline %s is %dx%d.",
                    name, size.actualWidth(), size.actualHeight(), baseline,
                    size.expectedWidth(), size.expectedHeight());
            case ImageComparison.PixelDiff pixels -> String.format(Locale.ROOT,
                    "Screenshot '%s' differs from its baseline %s: %d of %d pixels (%.2f%%) differ by more than %d"
                            + " per channel; at most %.2f%% may differ.",
                    name, baseline, pixels.differingPixels(), pixels.totalPixels(), pixels.diffRatio() * 100,
                    tolerance.maxChannelDelta(), tolerance.maxDiffPixelRatio() * 100);
        };
        if (!updateMode && comparison instanceof ImageComparison.PixelDiff pixels) {
            writePng(diffArtifact, pixels.diffImage());
            problem += "\n  diff:   " + diffArtifact;
        }
        failOrUpdate(baseline, actualPng, actualArtifact, problem);
    }

    private void failOrUpdate(Path baseline, byte[] actualPng, Path actualArtifact, String problem) {
        if (updateMode) {
            write(baseline, actualPng);
            log.info("Screenshot baseline written: {} ({})", baseline, problem);
            return;
        }
        write(actualArtifact, actualPng);
        throw new AssertionError(problem
                + "\n  actual: " + actualArtifact
                + "\nIf the new rendering is correct, " + UPDATE_HINT + " to accept it as the baseline.");
    }

    private static void requireSafeName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Screenshot name must match " + SAFE_NAME + ", got: " + name);
        }
    }

    private static Optional<BufferedImage> decode(byte[] png) {
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(png)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writePng(Path file, BufferedImage image) {
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
