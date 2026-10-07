package com.iitm.beacon.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point of UC-PURGE-REJECTED (decision 3): runs {@link
 * ModerationService#purgeExpiredRejections()} — weekly by default, Sundays at
 * 03:00 UTC — and logs one summary line with counts only, never an email or
 * other contact value (NFR-CONTACT-CONFIDENTIALITY). The cron expression
 * comes from {@code beacon.retention.cleanup-cron} ({@code
 * BEACON_RETENTION_CRON}); {@code -} switches the job off. A failed run is
 * not caught here: the scheduler logs it and keeps the schedule, so the next
 * run tries again.
 */
@Component
public class RejectedTestimonialCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RejectedTestimonialCleanupJob.class);

    private final ModerationService moderationService;

    public RejectedTestimonialCleanupJob(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @Scheduled(cron = "${beacon.retention.cleanup-cron:0 0 3 * * SUN}", zone = "UTC")
    public void purgeExpiredRejections() {
        RejectionPurgeResult result = moderationService.purgeExpiredRejections();
        log.info("Retention: purged {} long-rejected testimonials with {} photos",
                result.testimonialsPurged(), result.photosPurged());
    }
}
