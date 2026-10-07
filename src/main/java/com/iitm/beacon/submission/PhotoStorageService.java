package com.iitm.beacon.submission;

import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.testimonial.Photo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads and writes photo files on the mounted uploads volume (decision 2,
 * docs/architecture.md §7). Every upload is size-checked, then converted by
 * {@link PhotoImageProcessor} — which detects the format from the actual
 * bytes, never the client-declared {@code Content-Type} or filename
 * (NFR-UPLOAD-SPOOFING) — and stored as {@code <uuid>.webp} plus {@code
 * <uuid>-thumb.webp}. The uploaded original itself is never kept.
 *
 * <p>Files follow the surrounding transaction, so a rollback never leaves a
 * file without its {@code photo} row nor loses a live one (BL-017): the files
 * written inside a transaction are removed again if it rolls back, and a
 * delete inside a transaction waits for its commit. With no transaction
 * active (the startup {@link LegacyPhotoBackfill}, direct calls), there is
 * nothing to wait for: written files stay — removing them on a later
 * failure is up to the caller — and deletes happen right away.
 */
@Service
public class PhotoStorageService {

    private final PhotoStorageProperties photoStorageProperties;
    private final PhotoUrlResolver photoUrlResolver;
    private final PhotoImageProcessor photoImageProcessor;
    private final PhotoFileDeleter photoFileDeleter;
    private final Supplier<String> photoIds;

    @Autowired
    public PhotoStorageService(
            PhotoStorageProperties photoStorageProperties,
            PhotoUrlResolver photoUrlResolver,
            PhotoImageProcessor photoImageProcessor,
            PhotoFileDeleter photoFileDeleter) {
        this(
                photoStorageProperties,
                photoUrlResolver,
                photoImageProcessor,
                photoFileDeleter,
                () -> UUID.randomUUID().toString());
    }

    /** @param photoIds names each stored photo's pair of files (a random UUID outside tests) */
    PhotoStorageService(
            PhotoStorageProperties photoStorageProperties,
            PhotoUrlResolver photoUrlResolver,
            PhotoImageProcessor photoImageProcessor,
            PhotoFileDeleter photoFileDeleter,
            Supplier<String> photoIds) {
        this.photoStorageProperties = photoStorageProperties;
        this.photoUrlResolver = photoUrlResolver;
        this.photoImageProcessor = photoImageProcessor;
        this.photoFileDeleter = photoFileDeleter;
        this.photoIds = photoIds;
    }

    /**
     * Validates, converts and persists one uploaded photo. The size limit is
     * checked first, before the bytes are read. Inside a transaction, both
     * files are removed again if it rolls back — including a commit that
     * fails and is rolled back — but kept on commit, and kept when the
     * commit's outcome is unknown, since the row may then exist.
     *
     * @throws SubmissionValidationException if the file is over the size
     *     limit, isn't a supported image, or is too large in pixels
     */
    public StoredPhoto store(MultipartFile file) {
        long maxBytes = photoStorageProperties.maxPhotoSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new SubmissionValidationException(
                    "Photo exceeds the maximum allowed file size (" + PhotoUploadLimits.megabytes(maxBytes) + " MB).");
        }
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded photo.", e);
        }
        return save(photoImageProcessor.process(original));
    }

    /**
     * Converts a photo stored before the WebP pipeline (a root-relative
     * {@code Photo.filePath}) into a new full-size + thumbnail pair. The
     * original file is left in place — deleting it is up to the caller,
     * once the row points at the new files. Inside a transaction, the new
     * files are removed again on rollback, as for {@link #store}.
     *
     * @throws UncheckedIOException if the original can't be read
     * @throws SubmissionValidationException if it isn't a convertible image
     */
    public StoredPhoto convertLegacy(String relativePath) {
        byte[] original;
        try {
            original = Files.readAllBytes(root().resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the stored photo " + relativePath + ".", e);
        }
        return save(photoImageProcessor.process(original));
    }

    /**
     * Best-effort delete of a photo's full-size file and its thumbnail (a
     * legacy photo has none): never throws, since this must never break
     * the surrounding save transaction. Inside a transaction, the files the
     * photo has now are deleted only once it commits, and never on rollback;
     * with none active, right away. Delegates to {@link PhotoFileDeleter}.
     */
    public void delete(Photo photo) {
        String filePath = photo.getFilePath();
        String thumbnailPath = photo.getThumbnailPath();
        afterCommit(() -> {
            photoFileDeleter.delete(filePath);
            if (thumbnailPath != null) {
                photoFileDeleter.delete(thumbnailPath);
            }
        });
    }

    /**
     * Best-effort delete of one root-relative file; never throws if it's
     * already gone. Waits for the commit like {@link #delete(Photo)}.
     */
    public void delete(String relativePath) {
        afterCommit(() -> photoFileDeleter.delete(relativePath));
    }

    public String urlFor(String relativePath) {
        return photoUrlResolver.resolve(relativePath);
    }

    /** Writes both files; if the thumbnail can't be written, the full-size file is removed again. */
    private StoredPhoto save(ProcessedPhoto processed) {
        String id = photoIds.get();
        String fullName = id + ".webp";
        String thumbName = id + "-thumb.webp";
        Path root = root();
        try {
            Files.createDirectories(root);
            writeNew(root.resolve(fullName), processed.fullWebp());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the uploaded photo.", e);
        }
        try {
            writeNew(root.resolve(thumbName), processed.thumbWebp());
        } catch (IOException e) {
            // Right away, not after a commit: no row will ever point at it.
            photoFileDeleter.delete(fullName);
            throw new UncheckedIOException("Failed to store the uploaded photo's thumbnail.", e);
        }
        removeOnRollback(fullName, thumbName);
        return new StoredPhoto(fullName, thumbName, processed.width(), processed.height());
    }

    /** Inside a transaction, removes the just-written files again if it rolls back. */
    private void removeOnRollback(String fullName, String thumbName) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    photoFileDeleter.delete(fullName);
                    photoFileDeleter.delete(thumbName);
                }
            }
        });
    }

    /** Runs {@code deletion} once the surrounding transaction commits (never on rollback), or now if there's none. */
    private static void afterCommit(Runnable deletion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletion.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletion.run();
            }
        });
    }

    /** Creates {@code target} (never overwriting); a partially written file of ours is removed on failure. */
    private void writeNew(Path target, byte[] content) throws IOException {
        try {
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private Path root() {
        return Path.of(photoStorageProperties.rootPath());
    }
}
