package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests send CSRF tokens through {@link Csrf}, never spring-security-test's
 * {@code csrf()}: that one replaces the {@code CsrfFilter}'s cookie token
 * repository with a session-based test repository for the rest of the
 * cached application context, so the cookie-to-header tests sharing that
 * context (e.g. {@code config.CsrfProtectionTest}) would pass or fail
 * depending on the order the test classes run in.
 */
class CsrfHelperOnlyTest {

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final Pattern SPRING_TEST_CSRF =
            Pattern.compile("SecurityMockMvcRequestPostProcessors\\s*\\.\\s*csrf\\b");

    @Test
    void noTestUsesSpringSecurityTestsCsrfPostProcessor() throws IOException {
        assertThat(TEST_SOURCES).as("run from the project root").isDirectory();
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.endsWith(Path.of("testsupport", "CsrfHelperOnlyTest.java")))
                    .filter(CsrfHelperOnlyTest::usesSpringTestCsrf)
                    .toList();
        }

        assertThat(offenders).as("use testsupport.Csrf's csrfField()/csrfHeader() instead").isEmpty();
    }

    private static boolean usesSpringTestCsrf(Path file) {
        try {
            return SPRING_TEST_CSRF.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
