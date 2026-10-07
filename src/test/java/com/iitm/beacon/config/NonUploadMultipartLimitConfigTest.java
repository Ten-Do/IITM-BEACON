package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * Guards {@code beacon.web.non-upload-multipart-max-size} in {@code
 * src/main/resources/application.yml} — the largest multipart body accepted
 * anywhere but the submission endpoints ({@link NonUploadMultipartFilter}).
 * Like {@link UploadLimitsConfigTest}, it reads both application.yml files
 * directly (the test copy shadows the main one) and fails if they drift
 * apart.
 */
class NonUploadMultipartLimitConfigTest {

    private static final String KEY = "beacon.web.non-upload-multipart-max-size";

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

    /**
     * 16 KB: about four times the largest form outside the submission form
     * (the catalog's topic form — slug, label and a 500-character prompt —
     * even with 4-byte characters and the multipart overhead), and a
     * vanishing fraction of the submission endpoints' limit.
     */
    @Test
    void mainConfig_defaultsTo16Kilobytes_farBelowTheUploadLimit() throws Exception {
        Properties main = load(SecurityConfig.class);
        MockEnvironment noEnvironmentOverrides = new MockEnvironment();

        DataSize limit = DataSize.parse(noEnvironmentOverrides.resolvePlaceholders(rawMainValue()));
        String uploadLimit = main.getProperty("spring.servlet.multipart.max-request-size");
        DataSize uploads = DataSize.parse(noEnvironmentOverrides.resolvePlaceholders(uploadLimit));

        assertThat(limit).isEqualTo(DataSize.ofKilobytes(16));
        assertThat(limit.toBytes()).isLessThan(uploads.toBytes() / 1000);
    }

    @Test
    void mainConfig_isOverridableThroughBeaconNonUploadMultipartMaxSize() throws Exception {
        MockEnvironment environment =
                new MockEnvironment().withProperty("BEACON_NON_UPLOAD_MULTIPART_MAX_SIZE", "64KB");

        assertThat(environment.resolvePlaceholders(rawMainValue())).isEqualTo("64KB");
    }

    @Test
    void testConfig_repeatsTheMainValueVerbatim() throws Exception {
        assertThat(load(NonUploadMultipartLimitConfigTest.class).getProperty(KEY)).isEqualTo(rawMainValue());
    }
}
