package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * {@link RejectedTestimonialCleanupJob} (UC-PURGE-REJECTED, decision 3): a
 * thin scheduled entry point that runs the purge and logs one summary line.
 */
class RejectedTestimonialCleanupJobTest {

    private final ModerationService moderationService = mock(ModerationService.class);
    private final RejectedTestimonialCleanupJob job = new RejectedTestimonialCleanupJob(moderationService);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RejectedTestimonialCleanupJob.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void run_purgesOnce_andLogsOneInfoSummaryWithBothCounts() {
        when(moderationService.purgeExpiredRejections()).thenReturn(new RejectionPurgeResult(3, 7));

        job.purgeExpiredRejections();

        verify(moderationService).purgeExpiredRejections();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getArgumentArray()).containsExactly(3, 7);
        });
    }

    /** A run that finds nothing still says so, so an operator can tell the job ran. */
    @Test
    void run_nothingDue_stillLogsTheSummaryWithZeroes() {
        when(moderationService.purgeExpiredRejections()).thenReturn(new RejectionPurgeResult(0, 0));

        job.purgeExpiredRejections();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getArgumentArray()).containsExactly(0, 0);
        });
    }

    /**
     * A failed run isn't swallowed: it reaches the scheduler, which logs it
     * and keeps the schedule, so the next run tries again. No summary claims
     * a purge that was rolled back.
     */
    @Test
    void run_purgeFails_propagatesTheFailure_andLogsNoSummary() {
        when(moderationService.purgeExpiredRejections())
                .thenThrow(new DataAccessResourceFailureException("database unreachable"));

        assertThatThrownBy(job::purgeExpiredRejections).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(logs.list).isEmpty();
    }
}
