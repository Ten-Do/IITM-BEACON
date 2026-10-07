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
 * The dev/test OTP "delivery" (decision 4): the code is logged — a developer
 * logs in with it, no SMTP needed — but the recipient only as a masked
 * address ({@link LogMask}), never in plaintext (NFR-CONTACT-CONFIDENTIALITY).
 */
class LoggingOtpMailerTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(LoggingOtpMailer.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void logsTheMaskedEmailAndTheCodeAtInfoLevel() {
        new LoggingOtpMailer().sendOtp("visitor@example.com", "ABC234");

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("OTP for v***@example.com: ABC234");
        });
    }

    @Test
    void neverLogsTheFullAddress() {
        new LoggingOtpMailer().sendOtp("jane.doe@example.com", "ABC234");

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("jane.doe@example.com")
                .doesNotContain("jane.doe")
                .contains("ABC234"));
        assertThat(appender.list.get(0).getArgumentArray()).noneMatch(arg -> String.valueOf(arg).contains("jane.doe"));
    }

    @Test
    void oneCharacterLocalPart_isNotRevealedEither() {
        new LoggingOtpMailer().sendOtp("j@example.com", "ABC234");

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("OTP for ***@example.com: ABC234"));
    }
}
