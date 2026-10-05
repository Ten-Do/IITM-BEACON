package com.iitm.beacon.submission;

import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Converts, once, every photo stored before the WebP pipeline (a {@code
 * Photo} row without a thumbnail) into the same full-size + thumbnail WebP
 * pair new uploads get — synchronously at startup, when {@code
 * beacon.storage.backfill.enabled} is on.
 *
 * <p>Per photo: the original is converted and the new files written; then,
 * in the photo's own transaction, its row is re-pointed at them (only if
 * nothing changed the row meanwhile); only after that commit is the original
 * deleted. A photo that can't be converted (missing or undecodable file) is
 * logged and left exactly as it was — row and file — and the rest carry on.
 * Nothing but the photo row's file columns is ever written: no testimonial
 * status, {@code modified} flag or timestamp changes. Running it again only
 * retries the photos that failed.
 */
@Component
public class LegacyPhotoBackfill implements ApplicationRunner {

    /** Outcome of one pass: photos converted, and photos left as they were. */
    public record BackfillSummary(int converted, int failed) {

        public int total() {
            return converted + failed;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LegacyPhotoBackfill.class);
    private static final int DEFAULT_BATCH_SIZE = 50;

    private final PhotoRepository photoRepository;
    private final PhotoStorageService photoStorageService;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    public LegacyPhotoBackfill(
            PhotoRepository photoRepository,
            PhotoStorageService photoStorageService,
            PlatformTransactionManager transactionManager,
            PhotoStorageProperties photoStorageProperties) {
        this(photoRepository, photoStorageService, transactionManager, photoStorageProperties, DEFAULT_BATCH_SIZE);
    }

    LegacyPhotoBackfill(
            PhotoRepository photoRepository,
            PhotoStorageService photoStorageService,
            PlatformTransactionManager transactionManager,
            PhotoStorageProperties photoStorageProperties,
            int batchSize) {
        this.photoRepository = photoRepository;
        this.photoStorageService = photoStorageService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = photoStorageProperties.backfill().enabled();
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Legacy photo backfill is disabled (beacon.storage.backfill.enabled=false).");
            return;
        }
        backfill();
    }

    /** One full pass over every legacy photo, in id order, a batch at a time. */
    public BackfillSummary backfill() {
        int converted = 0;
        int failed = 0;
        long afterId = 0L;
        List<Photo> batch = photoRepository.findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(
                afterId, Limit.of(batchSize));
        while (!batch.isEmpty()) {
            for (Photo photo : batch) {
                if (convert(photo.getId(), photo.getFilePath())) {
                    converted++;
                } else {
                    failed++;
                }
                afterId = photo.getId();
            }
            batch = photoRepository.findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(
                    afterId, Limit.of(batchSize));
        }
        BackfillSummary summary = new BackfillSummary(converted, failed);
        if (summary.total() > 0) {
            log.info("Legacy photo backfill finished: {} converted, {} failed, {} total.",
                    summary.converted(), summary.failed(), summary.total());
        } else {
            log.debug("Legacy photo backfill finished: no legacy photos left.");
        }
        return summary;
    }

    private boolean convert(Long photoId, String oldFilePath) {
        StoredPhoto stored;
        try {
            stored = photoStorageService.convertLegacy(oldFilePath);
        } catch (RuntimeException e) {
            log.warn("Legacy photo backfill: could not convert photo {} ({}), left as is: {}",
                    photoId, oldFilePath, e.getMessage());
            return false;
        }
        int updated;
        try {
            Integer rows = transactionTemplate.execute(status -> photoRepository.replaceLegacyFile(
                    photoId, oldFilePath, stored.filePath(), stored.thumbnailPath(), stored.width(), stored.height()));
            updated = rows == null ? 0 : rows;
        } catch (RuntimeException e) {
            log.warn("Legacy photo backfill: could not update photo {} ({}), left as is: {}",
                    photoId, oldFilePath, e.getMessage());
            discard(stored);
            return false;
        }
        if (updated == 0) {
            log.warn("Legacy photo backfill: photo {} ({}) changed or was deleted meanwhile, left as is.",
                    photoId, oldFilePath);
            discard(stored);
            return false;
        }
        photoStorageService.delete(oldFilePath);
        return true;
    }

    private void discard(StoredPhoto stored) {
        photoStorageService.delete(stored.filePath());
        photoStorageService.delete(stored.thumbnailPath());
    }
}
