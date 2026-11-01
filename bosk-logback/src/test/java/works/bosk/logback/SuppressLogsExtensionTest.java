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

@SuppressLogs(value = SuppressLogsExtensionTest.ClassLevel.class, level = ERROR)
class SuppressLogsExtensionTest {
	static final class ClassLevel {}
	static final class MethodLevel {}
	static final class SecondMethodLevel {}
	static final class OtherThread {}

	LoggerContext loggerContext;
	ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		loggerContext = new LoggerContext();
		loggerContext.setName("SuppressLogsExtensionTest");
		loggerContext.start();
		loggerContext.addTurboFilter(new SuppressLogsFilter());

		appender = new ListAppender<>();
		appender.start();
		loggerContext.getLogger(ROOT_LOGGER_NAME).addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		loggerContext.stop();
	}

	@Test
	void classLevelAnnotationApplies() {
		logger(ClassLevel.class).warn("Expected noise");
		logger(ClassLevel.class).error("Expected noise");

		assertEquals(List.of(), recorded(ClassLevel.class), "The class-level annotation suppresses errors as well");
	}

	@SuppressLogs(value = MethodLevel.class, level = WARN)
	@SuppressLogs(value = SecondMethodLevel.class, level = WARN)
	@Test
	void methodLevelAnnotationsAddToTheClassLevel() {
		logger(MethodLevel.class).warn("Expected noise");
		logger(SecondMethodLevel.class).warn("Expected noise");
		logger(SecondMethodLevel.class).error("Something went wrong");

		assertEquals(List.of(), recorded(MethodLevel.class), "The first method-level logger is suppressed");
		assertEquals(List.of(ERROR), recorded(SecondMethodLevel.class), "The second method-level logger keeps errors");
	}

	@SuppressLogs(value = ClassLevel.class, level = WARN)
	@Test
	void methodLevelOverridesTheClassLevel() {
		logger(ClassLevel.class).warn("Expected noise");
		logger(ClassLevel.class).error("Something went wrong");

		assertEquals(List.of(ERROR), recorded(ClassLevel.class), "The method-level level takes precedence over the class-level level");
	}

	@SuppressLogs(OtherThread.class)
	@Test
	void otherThreadsAreNotSuppressed() throws InterruptedException {
		Thread thread = new Thread(() -> logger(OtherThread.class).warn("Expected noise from another thread"));
		thread.start();
		thread.join();

		assertEquals(List.of(WARN), recorded(OtherThread.class), "Suppression applies only to the test's thread");
	}

	private org.slf4j.Logger logger(Class<?> logger) {
		return loggerContext.getLogger(logger);
	}

	private List<Level> recorded(Class<?> logger) {
		return appender.list.stream()
			.filter(event -> event.getLoggerName().equals(logger.getName()))
			.map(ILoggingEvent::getLevel)
			.map(level -> Level.valueOf(level.toString()))
			.toList();
	}
}
