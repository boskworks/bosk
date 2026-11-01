package works.bosk.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.StateTreeNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.logging.MdcKeys.BOSK_INSTANCE_ID;

class BoskLogFilterTest {
	public record TestState() implements StateTreeNode {}

	private static final String CHATTY = "chatty";
	private static final String OUTSPOKEN = "outspoken";

	LoggerContext loggerContext;
	BoskLogFilter.LogController controller;
	Bosk<TestState> bosk;
	ListAppender<ILoggingEvent> appender;
	Logger chatty;
	Logger outspoken;

	@BeforeAll
	static void initializeLogging() {
		// Logback is not fully initialized until something asks it for a logger
		LoggerFactory.getLogger(BoskLogFilterTest.class);
	}

	@BeforeEach
	void setUp() throws Exception {
		loggerContext = new LoggerContext();
		loggerContext.setName("BoskLogFilterTest");
		loggerContext.start();
		loggerContext.addTurboFilter(new BoskLogFilter());

		appender = new ListAppender<>();
		appender.start();
		loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);

		chatty = loggerContext.getLogger(CHATTY);
		outspoken = loggerContext.getLogger(OUTSPOKEN);

		controller = new BoskLogFilter.LogController();
		bosk = new Bosk<>(
			"filter-test",
			TestState.class,
			_ -> new TestState(),
			BoskConfig.<TestState>builder()
				.driverFactory(BoskLogFilter.withController(controller))
				.build());
		MDC.put(BOSK_INSTANCE_ID, bosk.instanceID().toString());
	}

	@AfterEach
	void tearDown() {
		MDC.remove(BOSK_INSTANCE_ID);
		loggerContext.stop();
	}

	@Test
	void withoutSuppression_logsAreReported() {
		chatty.warn("Expected noise");
		chatty.error("Something went wrong");

		assertEquals(List.of(Level.WARN, Level.ERROR), recordedLevels(CHATTY), "Both events should be reported");
	}

	@Test
	void suppressLoggingInfo_keepsWarningsAndErrors() {
		controller.suppressLogging(Level.INFO, CHATTY);

		chatty.warn("Expected noise");
		chatty.error("Something went wrong");

		assertEquals(List.of(Level.WARN, Level.ERROR), recordedLevels(CHATTY), "Only events at or below INFO are suppressed");
	}

	@Test
	void suppressLoggingWarn_keepsErrors() {
		controller.suppressLogging(Level.WARN, CHATTY);

		chatty.warn("Expected noise");
		chatty.error("Something went wrong");

		assertEquals(List.of(Level.ERROR), recordedLevels(CHATTY), "An ERROR is more severe than WARN, so it is reported");
	}

	@Test
	void suppressLoggingError_suppressesEverything() {
		controller.suppressLogging(Level.ERROR, CHATTY);

		chatty.warn("Expected noise");
		chatty.error("Something went wrong");

		assertEquals(List.of(), recordedLevels(CHATTY), "Nothing is more severe than ERROR");
	}

	@Test
	void otherLoggersAreNotSuppressed() {
		controller.suppressLogging(Level.WARN, CHATTY);

		chatty.warn("Expected noise");
		outspoken.warn("Expected noise from a different logger");

		assertEquals(List.of(Level.WARN), recordedLevels(OUTSPOKEN), "Only the named logger is suppressed");
	}

	private List<Level> recordedLevels(String loggerName) {
		return appender.list.stream()
			.filter(event -> event.getLoggerName().equals(loggerName))
			.map(ILoggingEvent::getLevel)
			.map(level -> Level.valueOf(level.toString()))
			.toList();
	}
}
