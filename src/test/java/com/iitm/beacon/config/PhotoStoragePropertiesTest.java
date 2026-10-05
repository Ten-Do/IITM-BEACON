package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Confirms {@code beacon.storage.*} config actually binds into a {@link
 * PhotoStorageProperties} bean (decision 2, docs/architecture.md §7) via the
 * app's standard {@code @ConfigurationPropertiesScan}, the same pattern
 * already used for {@code VisitorOtpProperties}/{@code CryptoProperties} —
 * and that a nonsensical override stops the app at startup.
 */
@SpringBootTest
class PhotoStoragePropertiesTest {

    @Autowired
    private PhotoStorageProperties photoStorageProperties;

    @Test
    void bindsRootPathFromConfiguredProperties() {
        assertThat(photoStorageProperties.rootPath()).isNotBlank();
    }

    @Test
    void bindsMaxPhotosPerTestimonial_50() {
        assertThat(photoStorageProperties.maxPhotosPerTestimonial()).isEqualTo(50);
    }

    @Test
    void bindsMaxPhotosPerSection_5() {
        assertThat(photoStorageProperties.maxPhotosPerSection()).isEqualTo(5);
    }

    @Test
    void bindsMaxPhotoSizeBytes_20Megabytes() {
        assertThat(photoStorageProperties.maxPhotoSizeBytes()).isEqualTo(20_971_520L);
    }

    @Test
    void bindsTheProcessingDefaults() {
        assertThat(photoStorageProperties.maxPixels()).isEqualTo(250_000_000L);
        assertThat(photoStorageProperties.fullMaxEdge()).isEqualTo(2560);
        assertThat(photoStorageProperties.thumbMaxEdge()).isEqualTo(640);
        assertThat(photoStorageProperties.webpQuality()).isEqualTo(82);
        assertThat(photoStorageProperties.thumbWebpQuality()).isEqualTo(75);
    }

    @Test
    void legacyBackfill_isOffInTests() {
        assertThat(photoStorageProperties.backfill().enabled()).isFalse();
    }

    // -- startup validation of overrides --

    @EnableConfigurationProperties(PhotoStorageProperties.class)
    static class BindOnly {
    }

    private static final String[] VALID = {
        "beacon.storage.root-path=/data/uploads",
        "beacon.storage.max-photos-per-testimonial=50",
        "beacon.storage.max-photos-per-section=5",
        "beacon.storage.max-photo-size-bytes=20971520",
        "beacon.storage.max-pixels=250000000",
        "beacon.storage.full-max-edge=2560",
        "beacon.storage.thumb-max-edge=640",
        "beacon.storage.webp-quality=82",
        "beacon.storage.thumb-webp-quality=75",
        "beacon.storage.backfill.enabled=true"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(BindOnly.class)
            .withPropertyValues(VALID);

    @Test
    void validOverrides_bind() {
        runner.withPropertyValues("beacon.storage.webp-quality=100", "beacon.storage.thumb-webp-quality=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PhotoStorageProperties bound = context.getBean(PhotoStorageProperties.class);
                    assertThat(bound.webpQuality()).isEqualTo(100);
                    assertThat(bound.thumbWebpQuality()).isZero();
                    assertThat(bound.backfill().enabled()).isTrue();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "beacon.storage.webp-quality=101",
        "beacon.storage.webp-quality=-1",
        "beacon.storage.thumb-webp-quality=101",
        "beacon.storage.full-max-edge=0",
        "beacon.storage.full-max-edge=16384",
        "beacon.storage.thumb-max-edge=0",
        "beacon.storage.max-pixels=0",
        "beacon.storage.max-photos-per-testimonial=0",
        "beacon.storage.max-photos-per-testimonial=-1",
        "beacon.storage.max-photos-per-section=0",
        "beacon.storage.max-photos-per-section=-1"
    })
    void outOfRangeOverride_failsStartup(String override) {
        runner.withPropertyValues(override).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void onePhotoPerSectionAndPerTestimonial_isTheSmallestAcceptedLimit() {
        runner.withPropertyValues(
                        "beacon.storage.max-photos-per-testimonial=1", "beacon.storage.max-photos-per-section=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PhotoStorageProperties bound = context.getBean(PhotoStorageProperties.class);
                    assertThat(bound.maxPhotosPerTestimonial()).isEqualTo(1);
                    assertThat(bound.maxPhotosPerSection()).isEqualTo(1);
                });
    }

    @Test
    void largestWebpEdge_isAccepted() {
        runner.withPropertyValues("beacon.storage.full-max-edge=16383")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
