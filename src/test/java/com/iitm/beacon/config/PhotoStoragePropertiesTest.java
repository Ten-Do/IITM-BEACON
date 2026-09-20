package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Confirms {@code beacon.storage.*} config actually binds into a {@link
 * PhotoStorageProperties} bean (decision 2, docs/architecture.md §7) via the
 * app's standard {@code @ConfigurationPropertiesScan}, the same pattern
 * already used for {@code VisitorOtpProperties}/{@code CryptoProperties}.
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
    void bindsMaxPhotosPerTestimonialFromConfiguredProperties() {
        assertThat(photoStorageProperties.maxPhotosPerTestimonial()).isEqualTo(20);
    }

    @Test
    void bindsMaxPhotoSizeBytesFromConfiguredProperties() {
        assertThat(photoStorageProperties.maxPhotoSizeBytes()).isEqualTo(5_242_880L);
    }
}
