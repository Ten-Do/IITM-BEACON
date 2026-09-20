package com.iitm.beacon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code beacon.storage.*} config: the filesystem root photos are
 * written to/served from, plus the max number of photos per testimonial and
 * the max size of a single photo file (decision 2, docs/architecture.md §7).
 */
@ConfigurationProperties(prefix = "beacon.storage")
public record PhotoStorageProperties(String rootPath, int maxPhotosPerTestimonial, long maxPhotoSizeBytes) {
}
