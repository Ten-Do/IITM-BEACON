package com.iitm.beacon.config;

import com.iitm.beacon.domain.testimonial.Photo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort removal of photo files from the uploads root (decision 2,
 * docs/architecture.md §7). Lives in {@code config}, like {@link
 * PhotoUrlResolver}, so every slice that deletes photos — submission edits
 * and catalog deletes (decision 28) — shares it without depending on another
 * slice. Never throws: a file that can't be removed is only logged, since it
 * must never break the surrounding save or delete.
 */
@Component
public class PhotoFileDeleter {

    private static final Logger log = LoggerFactory.getLogger(PhotoFileDeleter.class);

    private final PhotoStorageProperties photoStorageProperties;

    public PhotoFileDeleter(PhotoStorageProperties photoStorageProperties) {
        this.photoStorageProperties = photoStorageProperties;
    }

    /** Deletes a photo's full-size file and its thumbnail (a legacy photo has none). */
    public void delete(Photo photo) {
        delete(photo.getFilePath());
        if (photo.getThumbnailPath() != null) {
            delete(photo.getThumbnailPath());
        }
    }

    /** Deletes one root-relative file; does nothing if it's already gone. */
    public void delete(String relativePath) {
        Path target = Path.of(photoStorageProperties.rootPath()).resolve(relativePath);
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (!deleted) {
                log.debug("Photo file already missing, nothing to delete: {}", relativePath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete photo file {}: {}", relativePath, e.getMessage());
        }
    }
}
