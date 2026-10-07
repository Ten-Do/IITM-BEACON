package com.iitm.beacon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on {@code @Scheduled} methods app-wide — today only {@code
 * moderation.RejectedTestimonialCleanupJob} (decision 3). Cross-cutting, so
 * it lives in {@code config} rather than in any one slice.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
