package works.bosk.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.WARN;

class SuppressLogsFilterTest {
	static final class Suppressed {}
	static final class Unaffected {}

	org.slf4j.Logger suppressed;
	org.slf4j.Logger unaffected;
	LoggerContext loggerContext;
	ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		loggerContext = new LoggerContext();
		loggerContext.setName("SuppressLogsFilterTest");
		loggerContext.start();
		loggerContext.addTurboFilter(new SuppressLogsFilter());

		appender = new ListAppender<>();
		appender.start();
		loggerContext.getLogger(ROOT_LOGGER_NAME).addAppender(appender);

		suppressed = loggerContext.getLogger(Suppressed.class);
		unaffected = loggerContext.getLogger(Unaffected.class);
	}

	@AfterEach
	void tearDown() {
		loggerContext.stop();
	}

	@Test
	void suppressesEventsAtOrBelowTheLevel() {
		try (var _ = SuppressLogsFilter.suppressForCurrentThread(WARN, Suppressed.class)) {
			suppressed.warn("Expected noise");
			suppressed.error("Something went wrong");
		}

		assertEquals(List.of(ERROR), recorded(Suppressed.class), "A warning is suppressed, but an error is reported");
	}

	@Test
	void suppressionEndsWhenTheScopeCloses() {
		try (var _ = SuppressLogsFilter.suppressForCurrentThread(WARN, Suppressed.class)) {
			suppressed.warn("Expected noise");
		}
		suppressed.warn("Unexpected noise");

		assertEquals(List.of(WARN), recorded(Suppressed.class), "A warning is reported again after the scope closes");
	}

	@Test
	void otherThreadsAreNotSuppressed() throws InterruptedException {
		try (var _ = SuppressLogsFilter.suppressForCurrentThread(WARN, Suppressed.class)) {
			Thread thread = new Thread(() -> suppressed.warn("Expected noise from another thread"));
			thread.start();
			thread.join();
		}

		assertEquals(List.of(WARN), recorded(Suppressed.class), "Suppression applies only to the thread that opened the scope");
	}

	@Test
	void otherLoggersAreNotSuppressed() {
		try (var _ = SuppressLogsFilter.suppressForCurrentThread(WARN, Suppressed.class)) {
			suppressed.warn("Expected noise");
			unaffected.warn("Unexpected noise");
		}

		assertEquals(List.of(WARN), recorded(Unaffected.class), "Only the named logger is suppressed");
	}

	@Test
	void theInnermostScopeThatNamesALoggerDecides() {
		try (var _ = SuppressLogsFilter.suppressForCurrentThread(ERROR, Suppressed.class)) {
			try (var _ = SuppressLogsFilter.suppressForCurrentThread(WARN, Suppressed.class)) {
				suppressed.warn("Expected noise");
				suppressed.error("Something went wrong");
			}
			suppressed.warn("Expected noise");

			assertEquals(List.of(ERROR), recorded(Suppressed.class), "The inner scope lets errors through, and the outer one still suppresses warnings");
		}
	}

	private List<Level> recorded(Class<?> logger) {
		return appender.list.stream()
			.filter(event -> event.getLoggerName().equals(logger.getName()))
			.map(ILoggingEvent::getLevel)
			.map(level -> Level.valueOf(level.toString()))
			.toList();
	}
}
