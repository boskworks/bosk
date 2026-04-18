package works.bosk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;
import org.slf4j.Marker;
import works.bosk.logback.ReplayLogsOnFailureExtension.Overrides;
import works.bosk.logback.ReplayLogsOnFailureExtension.QueueContents;

import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static java.util.Objects.requireNonNull;

/**
 * Records log events that would otherwise be suppressed so they can be replayed on test failure.
 * <p>
 * To enable this feature, first add this filter to your {@code logback.xml}:
 * <pre>
 *   &lt;turboFilter class="works.bosk.logback.RecordingTurboFilter"/&gt;
 * </pre>
 * Then, annotate your test class or method with {@link ReplayLogsOnFailure @ReplayLogsOnFailure}.
 * <h2>Effect</h2>
 * This filter offers the illusion that you retroactively turned on debug logging for a failed test,
 * even if the logs were originally filtered out by logback's normal configuration.
 * <h3>Caveats</h3>
 * <ul>
 *   <li>
 *       The filter does <em>not</em> cause level queries like {@code isDebugEnabled} to return true.
 *       This means that if your code is written like this:
 *       <pre>
 *       if (logger.isDebugEnabled()) {
 *           logger.debug(...);
 *       }
 *       </pre>
 *       then this event won't be computed or logged, even if the filter is configured to capture debug events.
 *   </li>
 *   <li>
 *       The filter buffers the events in memory and does not format the messages until replay time.
 *       This means parameters passed to the logging methods will be stored for some time,
 *       so if the objects are mutated after the logging event,
 *       those changes <em>will</em> be reflected in the replayed logs,
 *       making them differ from what you would have seen if the events were logged normally.
 *       It can also add to the memory overhead if the objects are large.
 *   </li>
 *   <li>
 *       The filter only captures events that reach it in the filter chain.
 *       If you have some other filter that drops events before they reach this one,
 *       those events won't be captured and thus won't be replayed on failure.
 *   </li>
 *   <li>
 *       The filter captures the MDC at the time of logging,
 *       which is necessary to avoid getting the wrong values at replay time,
 *       but it adds overhead even for events that are ultimately discarded.
 *       This overhead is O(n) in the number of MDC entries.
 *   </li>
 * </ul>
 * <h2>Configuration properties:</h2>
 * <ul>
 *   <li>
 *       {@code enabled} - Whether to record log events.
 *       Defaults to {@code false} so that only tests that explicitly
 *       opt in using {@link ReplayLogsOnFailure @ReplayLogsOnFailure}
 *       will have the overhead of buffering the logs.
 *   </li>
 *   <li>
 *       {@code level} - Finest-grained log level to record. Defaults to {@code DEBUG}
 *   </li>
 *   <li>
 *       {@code capacity} - Max events to buffer per test, to put a limit on memory consumption.
 *       If there are more events, the oldest will be dropped. Defaults to 1000.
 *   </li>
 *   <li>
 *       {@code routingKey} - MDC key used to route events to correct test buffer; details below.
 *       Defaults to {@code bosk.junit.testId}
 *   </li>
 * </ul>
 * <p>
 * The default configuration is equivalent to:
 * <pre>
 *   &lt;turboFilter class="works.bosk.logback.RecordingTurboFilter"&gt;
 *       &lt;enabled&gt;false&lt;/enabled&gt;
 *       &lt;level&gt;DEBUG&lt;/level&gt;
 *       &lt;capacity&gt;1000&lt;/capacity&gt;
 *       &lt;routingKey&gt;bosk.junit.testId&lt;/routingKey&gt;
 *   &lt;/turboFilter&gt;
 * </pre>
 *
 * The {@code enabled} and {@code capacity} properties can be overridden on a per-test basis using the {@link ReplayLogsOnFailure @ReplayLogsOnFailure} annotation's properties.
 * {@code routingKey} cannot be overridden because the routing key is the very thing that tells us which test is running (see below).
 * {@code level} also cannot be overridden per test because
 * it is crucial for performance that the filter quickly discards events that are too fine-grained.
 * <h2>Routing key</h2>
 *
 * The filter needs a way to associate log events with the correct test.
 * For simple single-threaded tests, the JUnit extension will set MDC key {@code bosk.junit.testId}
 * to the test's unique ID, and the filter can use that directly.
 * <p>
 * However, if the code you're testing is multithreaded, it's unlikely to propagate
 * the test ID to other threads, so the filter won't know which test the events belong to.
 * In this case, you can specify some other MDC key that your code <em>does</em> propagate across threads.
 * This is the <em>routing key</em>: the filter will attempt to associate the routing key with
 * the test ID whenever it sees them together in MDC.
 * Assuming the first log event for a test has both the test ID and routing key in MDC,
 * the filter will remember that association
 * and use it for subsequent events that only have the routing key.
 * <p>
 * Concretely, this works well for {@code bosk.instanceID},
 * which is an MDC key automatically propagated by bosk drivers.
 *
 * @see ReplayLogsOnFailure
 * @see ReplayLogsOnFailureExtension
 * @see works.bosk.logback
 */
public class RecordingTurboFilter extends TurboFilter {
	public static final String TEST_ID_KEY = "bosk.junit.testId";
	public static final String DEFAULT_ROUTING_KEY = "bosk.junit.testId";
	public static final int DEFAULT_CAPACITY = 1000;

	private boolean enabled = false;
	private int capacity = DEFAULT_CAPACITY;
	private Level level = Level.DEBUG;
	private String routingKey = DEFAULT_ROUTING_KEY;

	// These things are static so the test lifecycle methods don't need to
	// get their hands on the filter instance.

	/**
	 * If there's no entry for a given test ID, replay is disabled.
	 * That is different from an {@link Overrides} where everything is null,
	 * which means "use the filter's global defaults"
	 * (which might also mean replay is disabled depending on {@link #enabled}).
	 */
	private static final ConcurrentHashMap<String, Overrides> overridesByTestId = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, LogEventBuffer> buffersByTestId = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String> testIdsByRoutingKey = new ConcurrentHashMap<>();

	// Setters for logback configuration properties

	public void setEnabled(boolean enabled) { this.enabled = enabled; }

	/**
	 * The MDC key to use for routing log events to the correct test's buffer.
	 * <p>
	 * For single-threaded tests, "bosk.junit.testId" will work:
	 * the JUnit extension will set that MDC key as appropriate.
	 * For tests involving multiple threads, you'll need to pick an MDC key
	 * that the tested code propagates, and the extension will try to
	 * associate values of that key with the appropriate test ID.
	 * <p>
	 * For bosk tests specifically, "bosk.instanceID" is a good choice for this.
	 */
	public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

	public void setCapacity(int capacity) {
		if (capacity < 0) {
			throw new IllegalArgumentException("capacity must be non-negative");
		}
		this.capacity = capacity;
	}

	public void setLevel(Level level) { this.level = level; }

	static void putOverrides(String testId, Overrides overrides) {
		overridesByTestId.put(testId, overrides);
	}

	static void removeOverrides(String testId) {
		overridesByTestId.remove(testId);
		cleanupForTest(testId);
	}

	@Override
	public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
		// Note: Always return NEUTRAL. This lets events flow through the normal appender pipeline.

		// Check level early to quickly discard fine-grained events
		if (!level.isGreaterOrEqual(this.level)) {
			return NEUTRAL;
		}

		if (format == null) {
			// This happens for a call like isDebugEnabled.
			// We don't modify their behaviour.
			return NEUTRAL;
		}

		String testIdValue = MDC.get(TEST_ID_KEY);
		String routingKeyValue = MDC.get(this.routingKey);
		Overrides overrides;

		if (testIdValue == null) {
			// No test ID in MDC - check for routing key
			if (routingKeyValue == null) {
				// No way to associate this event with a test
				return NEUTRAL;
			} else {
				testIdValue = testIdsByRoutingKey.get(routingKeyValue);
				if (testIdValue == null) {
					// No test associated with this routing key
					return NEUTRAL;
				} else {
					// Ok, we figured out the test ID from routing key
					overrides = overridesByTestId.get(testIdValue);
				}
			}
		} else {
			// We have the test ID directly from MDC
			overrides = overridesByTestId.get(testIdValue);

			// If there's also a routing key in MDC, remember the association
			routingKeyValue = MDC.get(this.routingKey);
			if (routingKeyValue != null) {
				testIdsByRoutingKey.put(routingKeyValue, testIdValue);
			}
		}

		boolean isEnabled = (overrides != null && overrides.enabled() != null)
			? overrides.enabled()
			: this.enabled;
		if (!isEnabled) {
			return NEUTRAL;
		}

		int effectiveCapacity = (overrides != null && overrides.capacity() != null)
			? overrides.capacity()
			: this.capacity;

		if (effectiveCapacity <= 0) {
			// Bail out before bothering to instantiate the event object
			return NEUTRAL;
		}

		// Capture event for potential replay on failure
		LoggingEvent event = new LoggingEvent(
			logger.getName(),
			logger,
			level,
			format,
			t,
			params
		);
		event.setLoggerContext(logger.getLoggerContext());
		event.addMarker(marker);

		// We need to capture the current MDC. Can't wait until replay.
		event.setMDCPropertyMap(requireNonNull(MDC.getCopyOfContextMap(), // ew, this is O(n)
			"MDC can't be absent here!"));

		LogEventBuffer buffer = buffersByTestId
			.computeIfAbsent(testIdValue, _ -> new LogEventBuffer(effectiveCapacity));

		buffer.offer(event);

		return NEUTRAL;
	}

	QueueContents queueContents(String testId) {
		LogEventBuffer buffer = buffersByTestId.remove(testId);
		if (buffer == null) {
			return new QueueContents(List.of(), 0);
		}
		// This isn't atomic, but the dropped count isn't really important enough to worry
		return new QueueContents(buffer.queue, buffer.count.get() - buffer.queue.size());
	}

	static void cleanupForTest(String testId) {
		testIdsByRoutingKey.forEach((routingKeyValue, associatedTestId) -> {
			if (testId.equals(associatedTestId)) {
				testIdsByRoutingKey.remove(routingKeyValue);
			}
		});
		buffersByTestId.remove(testId);
	}

	static class LogEventBuffer {
		private final ConcurrentLinkedQueue<ILoggingEvent> queue = new ConcurrentLinkedQueue<>();
		private final int capacity;
		private final AtomicLong count = new AtomicLong(0);

		LogEventBuffer(int capacity) {
			this.capacity = capacity;
		}

		void offer(ILoggingEvent event) {
			if (this.count.incrementAndGet() > capacity) {
				// Once we're past capacity, remove the oldest event each time to make room for the new one.
				queue.poll();
			}
			queue.offer(event);
		}
	}

}
