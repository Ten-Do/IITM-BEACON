package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards {@code server.servlet.session.cookie.secure} — whether {@code
 * JSESSIONID} and {@code XSRF-TOKEN} carry {@code Secure} whatever the
 * request's own scheme. In production TLS ends at a reverse proxy and the app
 * sees plain {@code http}, so {@code application-prod.yml} turns it on; dev
 * and the tests run on {@code http} and leave it off. One setting, {@code
 * BEACON_COOKIE_SECURE}, overrides both. Like {@link
 * SessionTimeoutConfigTest}, it reads the files directly: the test copy of
 * {@code application.yml} shadows the main one and must repeat it verbatim.
 * {@link SecureCookiesTest} checks the value reaches both cookies.
 */
class SecureCookieConfigTest {

    private static final String KEY = "server.servlet.session.cookie.secure";

    private static Properties load(Class<?> fromOutputDirectoryOf, String file) throws URISyntaxException {
        Path outputDir = Path.of(fromOutputDirectoryOf.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve(file)));
        return yaml.getObject();
    }

    private static String raw(String file) throws URISyntaxException {
        String raw = load(SecurityConfig.class, file).getProperty(KEY);
        assertThat(raw).as(KEY + " must be configured in " + file).isNotNull();
        return raw;
    }

    @ParameterizedTest
    @CsvSource({"application.yml, false", "application-prod.yml, true"})
    void defaultIsOnInProductionOnly(String file, String expected) throws Exception {
        // Resolves ${ENV_VAR:default} to its default, ignoring whatever the machine's environment sets.
        assertThat(new MockEnvironment().resolvePlaceholders(raw(file))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"application.yml, true", "application-prod.yml, false"})
    void isOverridableThroughBeaconCookieSecure(String file, String override) throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("BEACON_COOKIE_SECURE", override);

        assertThat(environment.resolvePlaceholders(raw(file))).isEqualTo(override);
    }

    @Test
    void testConfig_repeatsTheMainValueVerbatim() throws Exception {
        assertThat(load(SecureCookieConfigTest.class, "application.yml").getProperty(KEY))
                .isEqualTo(raw("application.yml"));
    }
}
