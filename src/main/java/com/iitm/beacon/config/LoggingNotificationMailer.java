package com.iitm.beacon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test notification delivery: logs the message instead of emailing it
 * (docs/architecture.md §13 — "dev: OTP logged (not emailed)"). Active for
 * every profile except {@code prod}, which also covers the test profile
 * since no profile is active during {@code mvn test}.
 */
@Component
@Profile("!prod")
public class LoggingNotificationMailer implements NotificationMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationMailer.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("Notification email for {}: subject={}, body={}", toEmail, subject, body);
    }
}
