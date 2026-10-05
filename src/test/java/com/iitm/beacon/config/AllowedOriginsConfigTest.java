package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards {@code beacon.web.allowed-origins} in {@code
 * src/main/resources/application.yml} — this site's public origins for the
 * same-origin check ({@code common.web.SameOriginGuard}). Like {@link
 * SessionTimeoutConfigTest}, it reads both application.yml files directly
 * (the test copy shadows the main one) and fails if they drift apart. {@code
 * common.web.AllowedOriginsAppliedTest} checks the value reaches the check.
 */
class AllowedOriginsConfigTest {

    private static final String KEY = "beacon.web.allowed-origins";

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

    /** Empty: each request's own origin, right whenever no reverse proxy sits in front of the app. */
    @Test
    void mainConfig_defaultsToNone() throws Exception {
        assertThat(new MockEnvironment().resolvePlaceholders(rawMainValue())).isEmpty();
    }

    @Test
    void mainConfig_isOverridableThroughBeaconAllowedOrigins() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BEACON_ALLOWED_ORIGINS", "https://beacon.example,https://www.beacon.example");

        assertThat(environment.resolvePlaceholders(rawMainValue()))
                .isEqualTo("https://beacon.example,https://www.beacon.example");
    }

    @Test
    void testConfig_repeatsTheMainValueVerbatim() throws Exception {
        assertThat(load(AllowedOriginsConfigTest.class).getProperty(KEY)).isEqualTo(rawMainValue());
    }
}
