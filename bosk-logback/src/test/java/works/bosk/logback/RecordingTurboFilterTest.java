package works.bosk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.Injector;
import works.bosk.logback.ReplayLogsOnFailureExtension.Overrides;

import static ch.qos.logback.classic.Level.DEBUG;
import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.TRACE;
import static java.lang.annotation.ElementType.PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;
import static works.bosk.logback.RecordingTurboFilter.TEST_ID_KEY;
import static works.bosk.logback.RecordingTurboFilter.removeOverrides;

@InjectFrom({
	RecordingTurboFilterTest.BooleanInjector.class,
	RecordingTurboFilterTest.NullableBooleanInjector.class,
	RecordingTurboFilterTest.CapacityInjector.class,
	RecordingTurboFilterTest.FilterLevelInjector.class,
	RecordingTurboFilterTest.LogLevelInjector.class
})
class RecordingTurboFilterTest {
	static final String TEST_A = "TestA";
	static final String TEST_B = "TestB";
	static final String TEST = "Test";
	static final String LOGGER_NAME = "Test logger";
	static final String CONTEXT_NAME = "Test context";
	public static final String ROUTING_KEY = "testRoutingKey";

	private RecordingTurboFilter filter;
	private ch.qos.logback.classic.Logger testLogger;
	private ListAppender<ILoggingEvent> listAppender;

	record EventSnapshot(Level level, String message, String loggerName, String exceptionClassName) {
		static List<EventSnapshot> from(Collection<ILoggingEvent> events) {
			return events.stream()
				.map(e -> new EventSnapshot(
					e.getLevel(),
					e.getMessage(),
					e.getLoggerName(),
					e.getThrowableProxy() != null ? e.getThrowableProxy().getClassName() : null))
				.toList();
		}
	}

	@BeforeEach
	void setUp() {
		var loggerContext = new LoggerContext();
		loggerContext.setName(CONTEXT_NAME);
		loggerContext.start();

		filter = new RecordingTurboFilter();
		filter.setEnabled(true);
		filter.setRoutingKey(ROUTING_KEY);
		filter.setCapacity(1000);
		loggerContext.addTurboFilter(filter);

		testLogger = loggerContext.getLogger(LOGGER_NAME);
		testLogger.setLevel(DEBUG);

		listAppender = new ListAppender<>();
		listAppender.start();
		var root = loggerContext.getLogger(ROOT_LOGGER_NAME);
		root.addAppender(listAppender);
	}

	@AfterEach
	void teardown() {
		MDC.clear();
		removeOverrides(TEST_A);
		removeOverrides(TEST_B);
		removeOverrides(TEST);
	}

	@InjectedTest
	void enabled(boolean globalEnabled, Boolean perTestEnabled) {
		filter.setEnabled(globalEnabled);
		RecordingTurboFilter.putOverrides(TEST, new Overrides(perTestEnabled, null));

		MDC.put(TEST_ID_KEY, TEST);
		testLogger.debug("event");

		boolean expected = (perTestEnabled != null) ? perTestEnabled : globalEnabled;
		var actual = !filter.queueContents(TEST).events().isEmpty();

		assertEquals(expected, actual);
	}

	@InjectedTest
	void capacity(Integer perTestCapacity) {
		filter.setEnabled(true);
		filter.setCapacity(2);
		RecordingTurboFilter.putOverrides(TEST, new Overrides(true, perTestCapacity));

		MDC.put(TEST_ID_KEY, TEST);
		testLogger.debug("event 1");
		testLogger.debug("event 2");
		testLogger.debug("event 3");

		int expected = (perTestCapacity != null) ? perTestCapacity : 2;
		var actual = filter.queueContents(TEST).events().size();

		assertEquals(expected, actual);
	}

	@InjectedTest
	void logLevel_filtering(@FilterLevel Level filterLevel, @LogLevel Level logLevel) {
		filter.setEnabled(true);
		filter.setLevel(filterLevel);

		MDC.put(TEST_ID_KEY, TEST);
		logAt(testLogger, logLevel, "test message");

		boolean shouldCapture = logLevel.levelInt >= filterLevel.levelInt;
		var actual = !filter.queueContents(TEST).events().isEmpty();

		assertEquals(shouldCapture, actual);
	}

	@Test
	void sameTestId_sameQueue() {
		MDC.put(TEST_ID_KEY, TEST_A);

		testLogger.debug("debug message 1");
		testLogger.info("info message 1");

		var testAEvents = EventSnapshot.from(filter.queueContents(TEST_A).events());
		var testBEvents = EventSnapshot.from(filter.queueContents(TEST_B).events());

		assertEquals(List.of(
			new EventSnapshot(DEBUG, "debug message 1", LOGGER_NAME, null),
			new EventSnapshot(INFO, "info message 1", LOGGER_NAME, null)
		), testAEvents);

		assertEquals(List.of(), testBEvents);
	}

	@Test
	void differentTestIds_differentQueues() {
		MDC.put(TEST_ID_KEY, TEST_A);
		testLogger.debug("event for TestA");

		MDC.put(TEST_ID_KEY, TEST_B);
		testLogger.debug("event for TestB");

		var testAEvents = EventSnapshot.from(filter.queueContents(TEST_A).events());
		var testBEvents = EventSnapshot.from(filter.queueContents(TEST_B).events());
		assertEquals(List.of(
			new EventSnapshot(DEBUG, "event for TestA", LOGGER_NAME, null)
		), testAEvents);
		assertEquals(List.of(
			new EventSnapshot(DEBUG, "event for TestB", LOGGER_NAME, null)
		), testBEvents);
	}

	@Test
	void routingKey_isSticky() {
		MDC.put(ROUTING_KEY, "instance1");
		MDC.put(TEST_ID_KEY, TEST_A);
		testLogger.debug("first event");

		MDC.remove(TEST_ID_KEY);
		testLogger.debug("second event");

		var testAEvents = EventSnapshot.from(filter.queueContents(TEST_A).events());
		assertEquals(List.of(
			new EventSnapshot(DEBUG, "first event", LOGGER_NAME, null),
			new EventSnapshot(DEBUG, "second event", LOGGER_NAME, null)
		), testAEvents);
	}

	@Test
	void routingKey_reassociated() {
		MDC.put(ROUTING_KEY, "same-key");
		MDC.put(TEST_ID_KEY, TEST_A);
		testLogger.debug("event for TestA");

		MDC.remove(TEST_ID_KEY);
		testLogger.debug("event no test ID, should go to TestA");

		MDC.put(TEST_ID_KEY, TEST_B);
		testLogger.debug("event for TestB");

		MDC.remove(TEST_ID_KEY);
		testLogger.debug("event no test ID, should go to TestB");

		var testAEvents = EventSnapshot.from(filter.queueContents(TEST_A).events());
		var testBEvents = EventSnapshot.from(filter.queueContents(TEST_B).events());

		assertEquals(List.of(
			new EventSnapshot(DEBUG, "event for TestA", LOGGER_NAME, null),
			new EventSnapshot(DEBUG, "event no test ID, should go to TestA", LOGGER_NAME, null)
		), testAEvents);

		assertEquals(List.of(
			new EventSnapshot(DEBUG, "event for TestB", LOGGER_NAME, null),
			new EventSnapshot(DEBUG, "event no test ID, should go to TestB", LOGGER_NAME, null)
		), testBEvents);
	}

	@Test
	void cleanup_removesOnlyAssociated() {
		MDC.put(ROUTING_KEY, "instance1");
		MDC.put(TEST_ID_KEY, TEST_A);
		testLogger.debug("A");

		MDC.put(ROUTING_KEY, "instance2");
		MDC.put(TEST_ID_KEY, TEST_B);
		testLogger.debug("B");

		RecordingTurboFilter.cleanupForTest(TEST_A);

		var testAEvents = EventSnapshot.from(filter.queueContents(TEST_A).events());
		var testBEvents = EventSnapshot.from(filter.queueContents(TEST_B).events());

		assertEquals(List.of(), testAEvents);
		assertEquals(List.of(
			new EventSnapshot(DEBUG, "B", LOGGER_NAME, null)
		), testBEvents);
	}

	@Test
	void passThrough_toAppenders() {
		MDC.put(ROUTING_KEY, "key");
		MDC.put(TEST_ID_KEY, TEST);

		testLogger.info("info message");
		testLogger.warn("warn message");
		testLogger.error("error message");

		var events = EventSnapshot.from(filter.queueContents(TEST).events());
		var appenderEvents = EventSnapshot.from(listAppender.list);

		assertEquals(List.of(
			new EventSnapshot(INFO, "info message", LOGGER_NAME, null),
			new EventSnapshot(Level.WARN, "warn message", LOGGER_NAME, null),
			new EventSnapshot(Level.ERROR, "error message", LOGGER_NAME, null)
		), events);

		assertEquals(appenderEvents, events);
	}

	@Test
	void noTestId_notBuffered() {
		MDC.put(ROUTING_KEY, "some-key");

		testLogger.debug("should not be buffered");

		assertEquals(List.of(), EventSnapshot.from(filter.queueContents(TEST).events()));
	}

	@Test
	void recordedEvent_containsMdcAndMarker() {
		MDC.put(TEST_ID_KEY, TEST);
		MDC.put("bosk.name", "test-instance-123");
		MDC.put("customKey", "customValue");

		testLogger.debug("test message");

		var events = filter.queueContents(TEST).events();
		assertEquals(1, events.size());

		var event = (ch.qos.logback.classic.spi.LoggingEvent) events.iterator().next();
		assertEquals(Map.of(
			"bosk.junit.testId", TEST,
			"bosk.name", "test-instance-123",
			"customKey", "customValue"
		), event.getMDCPropertyMap());
	}

	@Test
	void recordedEvent_capturesMarker() {
		MDC.put(TEST_ID_KEY, TEST);
		Marker marker = MarkerFactory.getMarker("TEST_MARKER");

		testLogger.debug(marker, "test message with marker");

		var events = filter.queueContents(TEST).events();
		assertEquals(1, events.size());

		var event = (ch.qos.logback.classic.spi.LoggingEvent) events.iterator().next();
		assertEquals(marker, event.getMarker());
	}

	/**
	 * You might think it would be simpler to call {@code logger.atLevel(level).log(msg, args)},
	 * but sadly that calls the isXxxEnabled methods which we don't modify, and so the logs wouldn't be recorded.
	 */
	private static void logAt(org.slf4j.Logger logger, Level level, String msg, Object... args) {
		switch (toSlf4jEventLevel(level)) {
			case TRACE -> logger.trace(msg, args);
			case DEBUG -> logger.debug(msg, args);
			case INFO -> logger.info(msg, args);
			case WARN -> logger.warn(msg, args);
			case ERROR -> logger.error(msg, args);
		}
	}

	private static org.slf4j.event.Level toSlf4jEventLevel(Level level) {
		return org.slf4j.event.Level.valueOf(level.toString());
	}

	record BooleanInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement e, Class<?> t) {
			return t == boolean.class;
		}

		@Override
		public List<Boolean> values() {
			return List.of(true, false);
		}
	}

	record NullableBooleanInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement e, Class<?> t) {
			return t == Boolean.class;
		}

		@Override
		public List<Boolean> values() {
			return Stream.of(null, true, false).toList();
		}
	}

	record CapacityInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement e, Class<?> t) {
			return t == Integer.class;
		}

		@Override
		public List<Integer> values() {
			return Stream.of(null, 1, 2, 3).toList();
		}
	}

	record FilterLevelInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement e, Class<?> t) {
			return e.isAnnotationPresent(FilterLevel.class)
				&& t == Level.class;
		}

		@Override
		public List<Level> values() {
			return List.of(TRACE, DEBUG, INFO);
		}
	}

	record LogLevelInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement e, Class<?> t) {
			return e.isAnnotationPresent(LogLevel.class)
				&& t == Level.class;
		}

		@Override
		public List<Level> values() {
			return List.of(TRACE, DEBUG, INFO);
		}
	}

	@Target(PARAMETER)
	@Retention(RetentionPolicy.RUNTIME)
	@interface FilterLevel {}

	@Target(PARAMETER)
	@Retention(RetentionPolicy.RUNTIME)
	@interface LogLevel {}
}
