package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * Guards the multipart/Tomcat upload limits and the photo size/processing
 * limits in {@code src/main/resources/application.yml}. On the test
 * classpath that file is shadowed by {@code
 * src/test/resources/application.yml}, so no Spring test ever loads it —
 * this test reads both files directly, checks the production defaults
 * against the app's own business limits, and fails if
 * the test copy (which {@code submission.SubmissionUploadLimitsTest}
 * exercises over real HTTP) drifts from them.
 */
class UploadLimitsConfigTest {

    private static final List<String> UPLOAD_KEYS = List.of(
            "server.tomcat.max-part-count",
            "spring.servlet.multipart.max-file-size",
            "spring.servlet.multipart.max-request-size",
            "spring.servlet.multipart.resolve-lazily");

    /** Photo size/processing limits the test copy must repeat verbatim too (the backfill switch deliberately not). */
    private static final List<String> PHOTO_KEYS = List.of(
            "beacon.storage.max-photos-per-testimonial",
            "beacon.storage.max-photos-per-section",
            "beacon.storage.max-photo-size-bytes",
            "beacon.storage.max-pixels",
            "beacon.storage.full-max-edge",
            "beacon.storage.thumb-max-edge",
            "beacon.storage.webp-quality",
            "beacon.storage.thumb-webp-quality");

    /** Resolves {@code ${ENV_VAR:default}} to its default, ignoring whatever the machine's environment sets. */
    private final MockEnvironment noEnvironmentOverrides = new MockEnvironment();

    private static Properties load(Class<?> fromOutputDirectoryOf) throws URISyntaxException {
        Path outputDir = Path.of(fromOutputDirectoryOf.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve("application.yml")));
        return yaml.getObject();
    }

    private String defaultOf(Properties properties, String key) {
        String raw = properties.getProperty(key);
        assertThat(raw).as(key + " must be configured").isNotNull();
        return noEnvironmentOverrides.resolvePlaceholders(raw);
    }

    @Test
    void mainConfig_partCountDefault_isFarAboveTomcatsDefaultOf50() throws Exception {
        Properties main = load(SecurityConfig.class);

        assertThat(Integer.parseInt(defaultOf(main, "server.tomcat.max-part-count"))).isEqualTo(500);
        assertThat(main.getProperty("server.tomcat.max-part-count")).contains("BEACON_UPLOAD_MAX_PART_COUNT");
    }

    @Test
    void mainConfig_maxFileSize_isAboveThePhotoBusinessLimit() throws Exception {
        Properties main = load(SecurityConfig.class);
        long businessLimit = Long.parseLong(defaultOf(main, "beacon.storage.max-photo-size-bytes"));

        DataSize maxFileSize = DataSize.parse(defaultOf(main, "spring.servlet.multipart.max-file-size"));

        // Strictly above: a photo slightly over the business limit must still
        // reach PhotoStorageService and get its readable error.
        assertThat(maxFileSize.toBytes()).isGreaterThan(businessLimit);
        assertThat(maxFileSize).isEqualTo(DataSize.ofMegabytes(25));
    }

    @Test
    void mainConfig_photoBusinessLimit_is20Megabytes_overridableFromTheEnvironment() throws Exception {
        Properties main = load(SecurityConfig.class);

        assertThat(Long.parseLong(defaultOf(main, "beacon.storage.max-photo-size-bytes")))
                .isEqualTo(DataSize.ofMegabytes(20).toBytes());
        assertThat(main.getProperty("beacon.storage.max-photo-size-bytes")).contains("BEACON_PHOTO_MAX_SIZE_BYTES");
    }

    @Test
    void mainConfig_maxRequestSize_fitsEveryAllowedPhotoAtFullSize() throws Exception {
        Properties main = load(SecurityConfig.class);
        long maxPhotos = Long.parseLong(defaultOf(main, "beacon.storage.max-photos-per-testimonial"));
        long maxPhotoBytes = Long.parseLong(defaultOf(main, "beacon.storage.max-photo-size-bytes"));

        DataSize maxRequestSize = DataSize.parse(defaultOf(main, "spring.servlet.multipart.max-request-size"));

        assertThat(maxRequestSize.toBytes()).isGreaterThan(maxPhotos * maxPhotoBytes);
        // 50 photos x 20 MB, plus 10 MB for the form's text fields.
        assertThat(maxRequestSize).isEqualTo(DataSize.ofMegabytes(1010));
    }

    @Test
    void mainConfig_photoCountLimits_are50PerTestimonialAnd5PerSection_overridableFromTheEnvironment()
            throws Exception {
        Properties main = load(SecurityConfig.class);

        assertThat(defaultOf(main, "beacon.storage.max-photos-per-testimonial")).isEqualTo("50");
        assertThat(defaultOf(main, "beacon.storage.max-photos-per-section")).isEqualTo("5");
        assertThat(main.getProperty("beacon.storage.max-photos-per-testimonial")).contains("BEACON_PHOTO_MAX_COUNT");
        assertThat(main.getProperty("beacon.storage.max-photos-per-section"))
                .contains("BEACON_PHOTO_MAX_PER_SECTION");
    }

    @Test
    void mainConfig_perSectionLimit_isNoMoreThanThePerTestimonialLimit() throws Exception {
        Properties main = load(SecurityConfig.class);

        assertThat(Integer.parseInt(defaultOf(main, "beacon.storage.max-photos-per-section")))
                .isLessThanOrEqualTo(Integer.parseInt(defaultOf(main, "beacon.storage.max-photos-per-testimonial")));
    }

    @Test
    void mainConfig_photoProcessingDefaults_areOverridableFromTheEnvironment() throws Exception {
        Properties main = load(SecurityConfig.class);

        assertThat(defaultOf(main, "beacon.storage.max-pixels")).isEqualTo("250000000");
        assertThat(defaultOf(main, "beacon.storage.full-max-edge")).isEqualTo("2560");
        assertThat(defaultOf(main, "beacon.storage.thumb-max-edge")).isEqualTo("640");
        assertThat(defaultOf(main, "beacon.storage.webp-quality")).isEqualTo("82");
        assertThat(defaultOf(main, "beacon.storage.thumb-webp-quality")).isEqualTo("75");
        assertThat(main.getProperty("beacon.storage.max-pixels")).contains("BEACON_PHOTO_MAX_PIXELS");
        assertThat(main.getProperty("beacon.storage.full-max-edge")).contains("BEACON_PHOTO_FULL_MAX_EDGE");
        assertThat(main.getProperty("beacon.storage.thumb-max-edge")).contains("BEACON_PHOTO_THUMB_MAX_EDGE");
        assertThat(main.getProperty("beacon.storage.webp-quality")).contains("BEACON_PHOTO_WEBP_QUALITY");
        assertThat(main.getProperty("beacon.storage.thumb-webp-quality")).contains("BEACON_PHOTO_WEBP_THUMB_QUALITY");
    }

    @Test
    void legacyBackfill_isOnByDefaultInTheApp_butOffInTests() throws Exception {
        Properties main = load(SecurityConfig.class);
        Properties test = load(UploadLimitsConfigTest.class);

        assertThat(defaultOf(main, "beacon.storage.backfill.enabled")).isEqualTo("true");
        assertThat(main.getProperty("beacon.storage.backfill.enabled")).contains("BEACON_PHOTO_BACKFILL_ENABLED");
        assertThat(test.getProperty("beacon.storage.backfill.enabled")).isEqualTo("false");
    }

    @Test
    void mainConfig_resolvesMultipartLazily_soViewControllersCanCatchLimitBreaches() throws Exception {
        assertThat(defaultOf(load(SecurityConfig.class), "spring.servlet.multipart.resolve-lazily"))
                .isEqualTo("true");
    }

    @Test
    void testConfig_repeatsTheMainUploadLimitsVerbatim() throws Exception {
        Properties main = load(SecurityConfig.class);
        Properties test = load(UploadLimitsConfigTest.class);

        for (String key : UPLOAD_KEYS) {
            assertThat(test.getProperty(key)).as(key).isEqualTo(main.getProperty(key));
        }
    }

    @Test
    void testConfig_repeatsTheMainPhotoLimitsVerbatim() throws Exception {
        Properties main = load(SecurityConfig.class);
        Properties test = load(UploadLimitsConfigTest.class);

        for (String key : PHOTO_KEYS) {
            assertThat(test.getProperty(key)).as(key).isEqualTo(main.getProperty(key));
        }
    }
}
