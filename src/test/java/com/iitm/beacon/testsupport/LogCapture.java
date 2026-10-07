package com.iitm.beacon.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * Collects every log event of one test, from every logger (the root
 * logger's appender), so a test can assert what was — or wasn't — logged:
 * e.g. that a client error is never logged as a server error, or that an
 * email address never reaches the log. Register with
 * {@code @RegisterExtension}.
 */
public final class LogCapture implements BeforeEachCallback, AfterEachCallback {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger root;

    @Override
    public void beforeEach(ExtensionContext context) {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.list.clear();
        appender.start();
        root.addAppender(appender);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        root.detachAppender(appender);
        appender.stop();
    }

    /** The events logged at ERROR so far, as their formatted messages. */
    public List<String> errors() {
        return atLevel(Level.ERROR);
    }

    /** The events logged at WARN so far, as their formatted messages. */
    public List<String> warnings() {
        return atLevel(Level.WARN);
    }

    /**
     * Every event logged so far, at any level, as everything a log file
     * would show of it: level, logger, formatted message, and the class and
     * message of an attached exception and of each of its causes.
     */
    public List<String> all() {
        return appender.list.stream().map(LogCapture::describe).toList();
    }

    private List<String> atLevel(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(event -> event.getLoggerName() + ": " + event.getFormattedMessage())
                .toList();
    }

    private static String describe(ILoggingEvent event) {
        StringBuilder text = new StringBuilder()
                .append(event.getLevel()).append(' ')
                .append(event.getLoggerName()).append(": ")
                .append(event.getFormattedMessage());
        for (IThrowableProxy thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
            text.append(" | ").append(thrown.getClassName()).append(": ").append(thrown.getMessage());
        }
        return text.toString();
    }
}
