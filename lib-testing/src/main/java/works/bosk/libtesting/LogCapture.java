package works.bosk.libtesting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the log events emitted by a given logger during a test.
 * <p>
 * Attach a capture to a logger and retrieve the formatted messages it has emitted.
 * Closing the capture removes the appender. The logger's level is left untouched;
 * tests that need a particular level can set it on the logger returned by
 * {@link #logger}.
 */
public final class LogCapture implements AutoCloseable {
	private final Logger logger;
	private final ListAppender<ILoggingEvent> appender;

	/**
	 * Starts capturing the log events emitted by the logger for the given class.
	 */
	public static LogCapture capture(Class<?> loggerOwner) {
		Logger logger = logger(loggerOwner);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return new LogCapture(logger, appender);
	}

	/**
	 * The logback logger for the given class, for setting log levels in tests.
	 */
	public static Logger logger(Class<?> loggerOwner) {
		return logbackContext().getLogger(loggerOwner);
	}

	/**
	 * The formatted messages of the events captured so far.
	 */
	public List<String> formattedMessages() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Override
	public void close() {
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

	private LogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
		this.logger = logger;
		this.appender = appender;
	}
}
