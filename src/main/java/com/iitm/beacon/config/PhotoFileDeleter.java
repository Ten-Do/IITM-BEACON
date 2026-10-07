package com.iitm.beacon.config;

import com.iitm.beacon.domain.testimonial.Photo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort removal of photo files from the uploads root (decision 2,
 * docs/architecture.md §7). Lives in {@code config}, like {@link
 * PhotoUrlResolver}, so every slice that deletes photos — submission edits,
 * catalog deletes (decision 28) and the rejected-testimonial purge (decision
 * 3) — shares it without depending on another slice. Never throws: a file
 * that can't be removed is only logged, since it must never break the
 * surrounding save or delete. Each call reports whether the files were
 * there and are now gone, for a caller that flags a file already missing.
 */
@Component
public class PhotoFileDeleter {

    private static final Logger log = LoggerFactory.getLogger(PhotoFileDeleter.class);

    private final PhotoStorageProperties photoStorageProperties;

    public PhotoFileDeleter(PhotoStorageProperties photoStorageProperties) {
        this.photoStorageProperties = photoStorageProperties;
    }

    /**
     * Deletes a photo's full-size file and its thumbnail (a legacy photo has
     * none); the thumbnail is tried even if the full-size file fails.
     *
     * @return {@code true} if every file of the photo was there and is now
     *     gone; {@code false} if any was already missing or couldn't be removed
     */
    public boolean delete(Photo photo) {
        boolean fullSizeDeleted = delete(photo.getFilePath());
        boolean thumbnailDeleted = photo.getThumbnailPath() == null || delete(photo.getThumbnailPath());
        return fullSizeDeleted && thumbnailDeleted;
    }

    /**
     * Deletes one root-relative file. A blank path names no file — it would
     * resolve to the uploads root itself — so nothing is deleted.
     *
     * @return {@code true} if the file was there and is now gone; {@code
     *     false} if it was already missing, couldn't be removed, or the path
     *     is blank or not a valid path
     */
    public boolean delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            log.warn("Photo file path is blank, nothing to delete");
            return false;
        }
        try {
            Path target = Path.of(photoStorageProperties.rootPath()).resolve(relativePath);
            boolean deleted = Files.deleteIfExists(target);
            if (!deleted) {
                log.debug("Photo file already missing, nothing to delete: {}", relativePath);
            }
            return deleted;
        } catch (IOException | InvalidPathException e) {
            log.warn("Failed to delete photo file {}: {}", relativePath, e.getMessage());
            return false;
        }
    }
}
