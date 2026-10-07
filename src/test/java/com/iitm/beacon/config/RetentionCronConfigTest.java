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
 * Guards {@code beacon.retention.cleanup-cron} in {@code
 * src/main/resources/application.yml} — when the rejected-testimonial purge
 * runs (decision 3). Like {@link SessionTimeoutConfigTest}, it reads both
 * application.yml files directly (the test copy shadows the main one) and
 * fails if they drift apart. {@code
 * moderation.RejectedTestimonialCleanupScheduleTest} checks how the value
 * schedules the job.
 */
class RetentionCronConfigTest {

    private static final String KEY = "beacon.retention.cleanup-cron";

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
    void mainConfig_defaultsToSundaysAt0300() throws Exception {
        assertThat(new MockEnvironment().resolvePlaceholders(rawMainValue())).isEqualTo("0 0 3 * * SUN");
    }

    @Test
    void mainConfig_isOverridableThroughBeaconRetentionCron() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("BEACON_RETENTION_CRON", "0 30 1 * * *");

        assertThat(environment.resolvePlaceholders(rawMainValue())).isEqualTo("0 30 1 * * *");
    }

    @Test
    void testConfig_repeatsTheMainValueVerbatim() throws Exception {
        assertThat(load(RetentionCronConfigTest.class).getProperty(KEY)).isEqualTo(rawMainValue());
    }
}
