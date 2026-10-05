package com.iitm.beacon.submission;

import com.iitm.beacon.config.PhotoStorageProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The photo limits of a submission (decision 2), from {@code beacon.storage.*}:
 * at most {@code perTopic} photos per section (topic) and {@code perTestimonial}
 * in total — saved and new photos together — each file at most {@code
 * maxFileBytes}. The submission form states them next to every photo field
 * ({@link #summary()}) and hands them to its script, which enforces them before
 * anything is uploaded; {@link SubmissionService} and {@link
 * PhotoStorageService} enforce the same numbers on the server.
 */
public record PhotoUploadLimits(int perTopic, int perTestimonial, long maxFileBytes) {

    private static final BigDecimal BYTES_PER_MEGABYTE = BigDecimal.valueOf(1024L * 1024L);

    static PhotoUploadLimits from(PhotoStorageProperties properties) {
        return new PhotoUploadLimits(
                properties.maxPhotosPerSection(), properties.maxPhotosPerTestimonial(), properties.maxPhotoSizeBytes());
    }

    /** The per-file limit for people, e.g. {@code 20 MB} or {@code 1.5 MB}. */
    public String maxFileSizeLabel() {
        return megabytes(maxFileBytes) + " MB";
    }

    /** E.g. "Up to 5 photos per topic, 50 in total, up to 20 MB each. JPEG, PNG, WebP, GIF, TIFF or BMP." */
    public String summary() {
        return "Up to " + photos(perTopic) + " per topic, " + perTestimonial + " in total, up to "
                + maxFileSizeLabel() + " each. " + PhotoImageProcessor.ACCEPTED_FORMATS + ".";
    }

    /** {@code count} photos, e.g. "5 photos", "1 photo". */
    static String photos(int count) {
        return count + (count == 1 ? " photo" : " photos");
    }

    /** {@code bytes} in megabytes (MiB) to at most one decimal, e.g. {@code 20} or {@code 1.5}. */
    static String megabytes(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BYTES_PER_MEGABYTE, 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
