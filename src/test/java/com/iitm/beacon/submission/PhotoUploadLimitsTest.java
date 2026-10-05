package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link PhotoUploadLimits}: the photo limits the submission form states next
 * to every photo field and hands to its script — taken from {@code
 * beacon.storage.*}, never hard-coded.
 */
class PhotoUploadLimitsTest {

    private static final long TWENTY_MEGABYTES = 20L * 1024 * 1024;

    @Test
    void from_takesEveryLimitFromTheStorageProperties() {
        PhotoUploadLimits limits = PhotoUploadLimits.from(TestPhotoStorage.photoCountLimits(Path.of("/x"), 50, 5));

        assertThat(limits.perTopic()).isEqualTo(5);
        assertThat(limits.perTestimonial()).isEqualTo(50);
        assertThat(limits.maxFileBytes()).isEqualTo(TestPhotoStorage.DEFAULT_MAX_PHOTO_SIZE_BYTES);
    }

    @Test
    void summary_statesEveryLimitAndTheAcceptedFormats() {
        assertThat(new PhotoUploadLimits(5, 50, TWENTY_MEGABYTES).summary())
                .isEqualTo("Up to 5 photos per topic, 50 in total, up to 20 MB each."
                        + " JPEG, PNG, WebP, GIF, TIFF or BMP.");
    }

    @Test
    void summary_namesTheSameFormatsAsTheUnsupportedFormatError() {
        String summary = new PhotoUploadLimits(5, 50, TWENTY_MEGABYTES).summary();

        assertThat(PhotoImageProcessor.UNSUPPORTED_FORMAT_MESSAGE)
                .endsWith(summary.substring(summary.indexOf("JPEG")));
    }

    @Test
    void summary_singlePhotoLimit_isSingular() {
        assertThat(new PhotoUploadLimits(1, 1, TWENTY_MEGABYTES).summary())
                .startsWith("Up to 1 photo per topic, 1 in total, up to 20 MB each.");
    }

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @CsvSource({
        "20971520, 20 MB",
        "5242880, 5 MB",
        "1572864, 1.5 MB",
        "1600000, 1.5 MB",
        "26214400, 25 MB"
    })
    void maxFileSizeLabel_isInMegabytesToAtMostOneDecimal(long bytes, String label) {
        assertThat(new PhotoUploadLimits(5, 50, bytes).maxFileSizeLabel()).isEqualTo(label);
    }
}
