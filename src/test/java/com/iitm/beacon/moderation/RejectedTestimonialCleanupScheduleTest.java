package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitm.beacon.config.SchedulingConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * When {@link RejectedTestimonialCleanupJob} runs (decision 3): the cron
 * expression comes from {@code beacon.retention.cleanup-cron}, is evaluated
 * in UTC, defaults to Sundays at 03:00, and {@code -} switches the job off.
 * A minimal context — scheduling on, the job, a mocked service — so nothing
 * is ever purged.
 */
class RejectedTestimonialCleanupScheduleTest {

    /** A Wednesday. */
    private static final Instant WEDNESDAY_NOON = Instant.parse("2026-10-07T12:00:00Z");

    private final ModerationService moderationService = mock(ModerationService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, RejectedTestimonialCleanupJob.class)
            .withBean(ModerationService.class, () -> moderationService);

    private static Set<ScheduledTask> scheduledTasks(ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
    }

    private static CronTask onlyCronTask(ApplicationContext context) {
        Set<ScheduledTask> tasks = scheduledTasks(context);
        assertThat(tasks).singleElement().extracting(ScheduledTask::getTask).isInstanceOf(CronTask.class);
        return (CronTask) tasks.iterator().next().getTask();
    }

    private static Instant nextRunAfter(CronTask task, Instant now) {
        return task.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void noCronConfigured_runsWeeklyOnSundayAt0300Utc() {
        runner.run(context -> {
            CronTask task = onlyCronTask(context);

            assertThat(task.getExpression()).isEqualTo("0 0 3 * * SUN");
            assertThat(nextRunAfter(task, WEDNESDAY_NOON)).isEqualTo(Instant.parse("2026-10-11T03:00:00Z"));
        });
    }

    @Test
    void configuredCron_isTheOneScheduled_evaluatedInUtc() {
        runner.withPropertyValues("beacon.retention.cleanup-cron=0 30 1 * * *").run(context -> {
            CronTask task = onlyCronTask(context);

            assertThat(task.getExpression()).isEqualTo("0 30 1 * * *");
            assertThat(nextRunAfter(task, WEDNESDAY_NOON)).isEqualTo(Instant.parse("2026-10-08T01:30:00Z"));
        });
    }

    @Test
    void theScheduledTask_runsThePurge() {
        when(moderationService.purgeExpiredRejections()).thenReturn(new RejectionPurgeResult(0, 0));

        runner.run(context -> {
            onlyCronTask(context).getRunnable().run();

            verify(moderationService).purgeExpiredRejections();
        });
    }

    @Test
    void dashAsCron_switchesTheJobOff() {
        runner.withPropertyValues("beacon.retention.cleanup-cron=-").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(scheduledTasks(context)).isEmpty();
        });
    }

    /** A typo in the cron fails the start-up instead of silently never purging. */
    @Test
    void invalidCron_failsTheStartup() {
        runner.withPropertyValues("beacon.retention.cleanup-cron=every sunday").run(context ->
                assertThat(context).hasFailed());
    }
}
