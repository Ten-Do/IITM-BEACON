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
 * Guards {@code beacon.web.trusted-proxies} in {@code
 * src/main/resources/application.yml} — the reverse proxies whose {@code
 * X-Forwarded-For} and {@code X-Forwarded-Proto} the app believes ({@link
 * ClientAddressConfig}). Empty by default: nothing is believed. Like {@link
 * SessionTimeoutConfigTest}, it reads both application.yml files directly
 * (the test copy shadows the main one) and fails if they drift apart.
 */
class TrustedProxiesConfigTest {

    private static final String KEY = "beacon.web.trusted-proxies";

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
    void mainConfig_trustsNoProxyByDefault() throws Exception {
        assertThat(new MockEnvironment().resolvePlaceholders(rawMainValue())).isEmpty();
    }

    @Test
    void mainConfig_isOverridableThroughBeaconTrustedProxies() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("BEACON_TRUSTED_PROXIES", "172.18.0.2");

        assertThat(environment.resolvePlaceholders(rawMainValue())).isEqualTo("172.18.0.2");
    }

    @Test
    void testConfig_repeatsTheMainValueVerbatim() throws Exception {
        assertThat(load(TrustedProxiesConfigTest.class).getProperty(KEY)).isEqualTo(rawMainValue());
    }
}
