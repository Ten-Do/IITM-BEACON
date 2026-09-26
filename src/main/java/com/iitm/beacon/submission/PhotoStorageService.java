package com.iitm.beacon.submission;

import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads and writes photo files on the mounted uploads volume (decision 2,
 * docs/architecture.md §7). Content validation inspects the actual bytes via
 * {@code ImageIO}'s own format detection — never the client-declared {@code
 * Content-Type} or filename extension (NFR-UPLOAD-SPOOFING).
 */
@Service
public class PhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorageService.class);
    private static final String NOT_AN_IMAGE_MESSAGE = "Uploaded file is not a valid image.";

    private final PhotoStorageProperties photoStorageProperties;
    private final PhotoUrlResolver photoUrlResolver;

    public PhotoStorageService(PhotoStorageProperties photoStorageProperties, PhotoUrlResolver photoUrlResolver) {
        this.photoStorageProperties = photoStorageProperties;
        this.photoUrlResolver = photoUrlResolver;
    }

    /**
     * Validates, then persists, one uploaded photo, returning the filename
     * alone (root-relative, per decision 2) to store on {@code
     * Photo.filePath}.
     */
    public String store(MultipartFile file) {
        if (file.getSize() > photoStorageProperties.maxPhotoSizeBytes()) {
            throw new SubmissionValidationException("Photo exceeds the maximum allowed file size.");
        }
        String extension = detectImageExtension(file);
        String filename = UUID.randomUUID() + "." + extension;
        Path root = Path.of(photoStorageProperties.rootPath());
        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, root.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the uploaded photo.", e);
        }
        return filename;
    }

    /**
     * Best-effort delete: never throws if the file is already gone, since
     * this must never break the surrounding save transaction.
     */
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

    public String urlFor(String relativePath) {
        return photoUrlResolver.resolve(relativePath);
    }

    private String detectImageExtension(MultipartFile file) {
        try (InputStream in = file.getInputStream();
                ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                throw new SubmissionValidationException(NOT_AN_IMAGE_MESSAGE);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new SubmissionValidationException(NOT_AN_IMAGE_MESSAGE);
            }
            return readers.next().getFormatName().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new SubmissionValidationException(NOT_AN_IMAGE_MESSAGE);
        }
    }
}
