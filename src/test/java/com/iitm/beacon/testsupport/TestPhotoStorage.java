package com.iitm.beacon.testsupport;

import com.iitm.beacon.config.PhotoStorageProperties;
import java.nio.file.Path;

/**
 * {@link PhotoStorageProperties} for tests that build the photo storage by
 * hand (their own uploads root, photo count or size limit): everything else
 * gets the same defaults as {@code application.yml}.
 */
public final class TestPhotoStorage {

    public static final long DEFAULT_MAX_PHOTO_SIZE_BYTES = 20_971_520L;
    public static final int DEFAULT_MAX_PHOTOS = 50;
    public static final int DEFAULT_MAX_PHOTOS_PER_SECTION = 5;

    private TestPhotoStorage() {
    }

    public static PhotoStorageProperties properties(Path root) {
        return properties(root, DEFAULT_MAX_PHOTOS, DEFAULT_MAX_PHOTO_SIZE_BYTES);
    }

    /** Defaults, with this per-testimonial photo count and file size limit (5 photos per section). */
    public static PhotoStorageProperties properties(Path root, int maxPhotos, long maxPhotoSizeBytes) {
        return properties(root, maxPhotos, DEFAULT_MAX_PHOTOS_PER_SECTION, maxPhotoSizeBytes, false);
    }

    /** Defaults, with these per-testimonial and per-section photo count limits. */
    public static PhotoStorageProperties photoCountLimits(Path root, int maxPhotos, int maxPhotosPerSection) {
        return properties(root, maxPhotos, maxPhotosPerSection, DEFAULT_MAX_PHOTO_SIZE_BYTES, false);
    }

    /** Defaults, with the startup conversion of legacy photos switched on or off. */
    public static PhotoStorageProperties properties(Path root, boolean backfillEnabled) {
        return properties(
                root,
                DEFAULT_MAX_PHOTOS,
                DEFAULT_MAX_PHOTOS_PER_SECTION,
                DEFAULT_MAX_PHOTO_SIZE_BYTES,
                backfillEnabled);
    }

    private static PhotoStorageProperties properties(
            Path root, int maxPhotos, int maxPhotosPerSection, long maxPhotoSizeBytes, boolean backfillEnabled) {
        return new PhotoStorageProperties(
                root.toString(),
                maxPhotos,
                maxPhotosPerSection,
                maxPhotoSizeBytes,
                250_000_000L,
                2560,
                640,
                82,
                75,
                new PhotoStorageProperties.Backfill(backfillEnabled));
    }
}
