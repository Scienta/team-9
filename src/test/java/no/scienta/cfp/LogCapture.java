package no.scienta.cfp;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Collects what one logger writes, for tests of classes whose only output is a log line. */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogCapture(String name) {
        logger = (Logger) LoggerFactory.getLogger(name);
        appender.start();
        logger.addAppender(appender);
    }

    /** The formatted INFO (and above) lines, oldest first. */
    List<String> info() {
        return appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
