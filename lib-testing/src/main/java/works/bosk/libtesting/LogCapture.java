package works.bosk.libtesting;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the log events emitted by a given logger during a test.
 * <p>
 * Configure the logger to emit at trace level, attach an appender to it,
 * and retrieve the events it has captured. Closing the capture removes the
 * appender and restores the logger's previous level.
 */
public final class LogCapture implements AutoCloseable {
	private final Logger logger;
	private final Level previousLevel;
	private final ListAppender<ILoggingEvent> appender;

	/**
	 * Starts capturing trace-level events emitted by the logger for the given class.
	 */
	public static LogCapture captureTrace(Class<?> loggerOwner) {
		LoggerContext context = logbackContext();
		Logger logger = context.getLogger(loggerOwner);
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.TRACE);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return new LogCapture(logger, previousLevel, appender);
	}

	/**
	 * The formatted messages of the events captured so far.
	 */
	public List<String> formattedMessages() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Override
	public void close() {
		logger.setLevel(previousLevel);
		logger.detachAppender(appender);
	}

	/**
	 * Tests in the same JVM run concurrently, so logback may still be starting up
	 * when this is called; wait for it to finish rather than racing it.
	 */
	private static LoggerContext logbackContext() {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (true) {
			Object factory = LoggerFactory.getILoggerFactory();
			if (factory instanceof LoggerContext context) {
				return context;
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Logback did not finish starting up; got " + factory.getClass().getSimpleName());
			}
			Thread.onSpinWait();
		}
	}

	private LogCapture(Logger logger, Level previousLevel, ListAppender<ILoggingEvent> appender) {
		this.logger = logger;
		this.previousLevel = previousLevel;
		this.appender = appender;
	}
}
