package com.iitm.beacon.common.clock;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single {@link Clock} bean used throughout the app instead of
 * calling {@code Instant.now()}/{@code LocalDate.now()} directly, so
 * time-based logic (OTP expiry, retention windows, error timestamps) stays
 * deterministic and testable (see docs/architecture.md §16).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
