package com.iitm.beacon.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code beacon.storage.*} config (decision 2, docs/architecture.md
 * §7): the filesystem root photos are written to/served from, the max number
 * of photos per testimonial and per section (topic) — kept photos and new
 * uploads together — and the max size of a single uploaded file, plus
 * how every upload is normalised ({@code submission.PhotoImageProcessor}):
 * the largest accepted image in pixels (decompression-bomb guard), the long
 * edge of the full-size and thumbnail WebP outputs, their lossy WebP quality
 * (0-100), and whether legacy photos are converted at startup.
 *
 * <p>Validated at startup, so a bad environment override stops the app
 * instead of failing every upload. 16383 px is WebP's own size limit.
 */
@Validated
@ConfigurationProperties(prefix = "beacon.storage")
public record PhotoStorageProperties(
        String rootPath,
        @Positive int maxPhotosPerTestimonial,
        @Positive int maxPhotosPerSection,
        long maxPhotoSizeBytes,
        @Positive long maxPixels,
        @Min(1) @Max(16383) int fullMaxEdge,
        @Min(1) @Max(16383) int thumbMaxEdge,
        @Min(0) @Max(100) int webpQuality,
        @Min(0) @Max(100) int thumbWebpQuality,
        @NotNull @Valid Backfill backfill) {

    /** {@code beacon.storage.backfill.*}: the one-off conversion of photos stored before WebP (LegacyPhotoBackfill). */
    public record Backfill(boolean enabled) {
    }
}
