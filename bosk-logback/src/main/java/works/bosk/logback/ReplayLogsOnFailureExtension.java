package works.bosk.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.OutputStreamAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;
import static works.bosk.logback.RecordingTurboFilter.Overrides;
import static works.bosk.logback.RecordingTurboFilter.QueueContents;
import static works.bosk.logback.RecordingTurboFilter.TEST_ID_KEY;

/**
 * JUnit 5 extension that implements log replay on failure.
 * <p>
 * This class is invoked automatically by {@link ReplayLogsOnFailure} and
 * coordinates with {@link RecordingTurboFilter}:
 * <ul>
 *   <li>
 *       Before each test, it sets the MDC key {@code bosk.junit.testId} to the test's unique ID,
 *       allowing the filter to associate log events with this test.
 *   </li>
 *   <li>
 *       After each test, if the test failed, it retrieves the recorded events from the filter
 *       and prints them to the console.
 *   </li>
 * </ul>
 *
 * @see ReplayLogsOnFailure
 * @see RecordingTurboFilter
 * @see works.bosk.logback
 */
public class ReplayLogsOnFailureExtension implements BeforeEachCallback, AfterEachCallback {

	static final int UNSPECIFIED_CAPACITY = -1;

	@Override
	public void beforeEach(ExtensionContext context) {
		String testId = context.getUniqueId();
		MDC.put(TEST_ID_KEY, testId);
		RecordingTurboFilter.putOverrides(testId, resolveOverrides(context));
	}

	@Override
	public void afterEach(ExtensionContext context) {
		var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

		String testId = context.getUniqueId();

		if (context.getExecutionException().isPresent()) {
			RecordingTurboFilter filter = findFilter(loggerContext);
			if (filter != null) {
				// This is what it's all about
				replay(filter.queueContents(testId));
			}
		}

		RecordingTurboFilter.removeOverrides(testId);
		MDC.remove(TEST_ID_KEY);
	}

	private Overrides resolveOverrides(ExtensionContext context) {
		var element = context.getElement().orElse(null);
		if (element == null) {
			// No annotated element, no overrides
			return Overrides.NONE;
		}

		var annotation = findAnnotation(element, ReplayLogsOnFailure.class).orElse(null);
		if (annotation == null) {
			var testClass = context.getRequiredTestClass();
			annotation = testClass.getAnnotation(ReplayLogsOnFailure.class);
		}
		if (annotation == null) {
			// No annotation, no overrides
			return Overrides.NONE;
		}

		// For the enabled flag, the annotation always overrides the global default.
		// The presence of the annotation is enough to indicate that the user has opted in.
		boolean enabled = annotation.enabled();

		// For capacity, if unspecified, use the global default from the filter.
		Integer capacity = (annotation.capacity() == UNSPECIFIED_CAPACITY)
			? null
			: annotation.capacity();

		return new Overrides(enabled, capacity);
	}

	@Nullable
	private static RecordingTurboFilter findFilter(LoggerContext loggerContext) {
		for (var f : loggerContext.getTurboFilterList()) {
			if (f instanceof RecordingTurboFilter) {
				return (RecordingTurboFilter) f;
			}
		}
		return null;
	}

	private void replay(QueueContents queueContents) {
		if (queueContents.events().isEmpty()) {
			// This happens in two cases:
			// - The test emitted no logs
			// - Replay is disabled for this test
			// In either case, announcing that we're replaying logs would be confusing.
			return;
		}
		StringBuilder header = new StringBuilder("--- TEST FAILED: REPLAYING LOGS ---");
		var dropped = queueContents.dropped();
		if (dropped > 0) {
			var totalEvents = queueContents.events().size() + dropped;
			header
				.append(" (")
				.append(dropped)
				.append(" of ")
				.append(totalEvents)
				.append(" events dropped due to capacity limit)");
		}
		System.out.println(header);

		var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
//		var rootLogger = loggerContext.getLogger(ROOT_LOGGER_NAME);
		var patternLayout = findPatternLayout(loggerContext);

		// I tried sending the events to the proper appenders, and that works when a single test
		// is run in isolation, but when the whole suite is run, this caused bizarre scrambling
		// of the order of the logs, even within those emitted by a single thread.
		// I think I've broken some contract of Logback here, but I'm not sure how exactly.
		// In the meantime, we just print to stdout.

		for (var event : queueContents.events()) {
			if (false) {
//				var eventLogger = loggerContext.getLogger(event.getLoggerName());
//				var appenderMap = eventLogger.iteratorForAppenders();
//
//				if (!appenderMap.hasNext()) {
//					appenderMap = rootLogger.iteratorForAppenders();
//				}
//
//				while (appenderMap.hasNext()) {
//					var appender = appenderMap.next();
//					appender.doAppend(event);
//				}
			} else {
				System.out.print(patternLayout.doLayout(event));
			}
		}
		System.out.println("--- END OF REPLAYED LOGS ---");
		System.out.flush();
	}

	private PatternLayout findPatternLayout(LoggerContext loggerContext) {
		var rootLogger = loggerContext.getLogger(ROOT_LOGGER_NAME);
		var appenderIt = rootLogger.iteratorForAppenders();

		while (appenderIt.hasNext()) {
			var appender = appenderIt.next();
			if (appender instanceof OutputStreamAppender) {
				var encoder = ((OutputStreamAppender<?>) appender).getEncoder();
				if (encoder instanceof PatternLayoutEncoder) {
					var layout = ((PatternLayoutEncoder) encoder).getLayout();
					if (layout instanceof PatternLayout && layout.isStarted()) {
						return (PatternLayout) layout;
					}
				}
			}
		}
		throw new IllegalStateException("Could not find the PatternLayout in the root logger's appenders");
	}

}
