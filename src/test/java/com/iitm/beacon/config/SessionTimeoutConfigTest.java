package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the HTTP session timeout in {@code src/main/resources/application.yml}
 * — how long an idle admin or visitor stays logged in. On the test classpath
 * that file is shadowed by {@code src/test/resources/application.yml}, so no
 * Spring test ever loads it; like {@link UploadLimitsConfigTest}, this test
 * reads both files directly and fails if the test copy drifts from the main
 * one. {@link SessionTimeoutAppliedTest} checks the value reaches the
 * servlet container.
 */
class SessionTimeoutConfigTest {

    private static final String KEY = "server.servlet.session.timeout";

    private static Properties load(Class<?> fromOutputDirectoryOf) throws URISyntaxException {
        Path outputDir = Path.of(fromOutputDirectoryOf.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve("application.yml")));
        return yaml.getObject();
    }

    private static String rawMainValue() throws URISyntaxException {
        String raw = load(SecurityConfig.class).getProperty(KEY);
        assertThat(raw).as(KEY + " must be configured").isNotNull();
        return raw;
    }

    @Test
    void mainConfig_defaultsTo24Hours() throws Exception {
        // Resolves ${ENV_VAR:default} to its default, ignoring whatever the machine's environment sets.
        String resolved = new MockEnvironment().resolvePlaceholders(rawMainValue());

        assertThat(DurationStyle.detectAndParse(resolved)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void mainConfig_isOverridableThroughBeaconSessionTimeout() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("BEACON_SESSION_TIMEOUT", "30m");

        String resolved = environment.resolvePlaceholders(rawMainValue());

        assertThat(DurationStyle.detectAndParse(resolved)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void testConfig_repeatsTheMainSessionTimeoutVerbatim() throws Exception {
        assertThat(load(SessionTimeoutConfigTest.class).getProperty(KEY)).isEqualTo(rawMainValue());
    }
}
