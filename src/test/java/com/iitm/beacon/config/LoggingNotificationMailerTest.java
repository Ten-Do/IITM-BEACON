package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The dev/test notification "delivery": subject and body are logged as
 * they are (a moderation outcome, no personal data), the recipient only as
 * a masked address ({@link LogMask}), never in plaintext
 * (NFR-CONTACT-CONFIDENTIALITY).
 */
class LoggingNotificationMailerTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(LoggingNotificationMailer.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void logsTheMaskedRecipientSubjectAndBodyAtInfoLevel() {
        new LoggingNotificationMailer()
                .send("visitor@example.com", "Your testimonial was approved", "Thanks for sharing your story.");

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("Notification email for v***@example.com:"
                    + " subject=Your testimonial was approved, body=Thanks for sharing your story.");
        });
    }

    @Test
    void neverLogsTheFullAddress() {
        new LoggingNotificationMailer().send("jane.doe@example.com", "Subject", "Body");

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("jane.doe@example.com")
                .doesNotContain("jane.doe"));
        assertThat(appender.list.get(0).getArgumentArray()).noneMatch(arg -> String.valueOf(arg).contains("jane.doe"));
    }
}
