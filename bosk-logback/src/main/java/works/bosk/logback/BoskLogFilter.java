package works.bosk.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import works.bosk.Bosk;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.logging.MdcKeys;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static java.util.stream.Collectors.toMap;
import static works.bosk.logging.MdcKeys.BOSK_INSTANCE_ID;

/**
 * A Logback {@link TurboFilter} that provides per-bosk log suppression.
 * Intended to suppress expected warnings and errors during testing.
 * <p>
 * A {@link Bosk} whose driver stack includes {@link #withController}
 * will be able to suppress expected log events using
 * {@link LogController#suppressLogging} without affecting other logs.
 * <p>
 * This class infers that a log message is associated with a particular bosk
 * by checking the MDC for the key {@link MdcKeys#BOSK_INSTANCE_ID},
 * which you can set using {@link works.bosk.logging.MappedDiagnosticContext#setupMDC setupMDC}.
 * This is part of the driver conformance test,
 * so all drivers should be propagating this MDC key.
 * <p>
 * Log levels are determined using the following precedence:
 * <ol>
 *     <li>
 *         If the specific logger is configured with some level,
 *         that level is used;
 *     </li>
 *     <li>
 *         otherwise, if the logger is associated with a bosk whose driver
 *         was configured with {@link #withController} and that controller
 *         has an override for that specific logger, that override is used;
 *     </li>
 *     <li>
 *         otherwise, the usual Logback rules apply, which means
 *         that the logger inherits the level from its ancestors.
 *     </li>
 * </ol>
 *
 */
public class BoskLogFilter extends TurboFilter {
	private static final ConcurrentHashMap<String, LogController> controllersByBoskID = new ConcurrentHashMap<>();

	/**
	 * Decides which log events are suppressed for each bosk whose driver stack registers it.
	 */
	public static final class LogController {
		/**
		 * The most severe suppressed level for each logger name.
		 */
		final Map<String, ch.qos.logback.classic.Level> overrides = new ConcurrentHashMap<>();

		/**
		 * Suppresses events from the given loggers at or below {@code level},
		 * for logs attributed to the bosk that owns this controller.
		 * <p>
		 * For example, {@code suppressLogging(Level.WARN, MainDriver.class)}
		 * suppresses warnings and anything less severe, but still reports errors.
		 * {@link Level#ERROR} suppresses everything, because nothing is more severe.
		 *
		 * @param level   the most severe level to suppress
		 * @param loggers the loggers whose events are suppressed at that level and below
		 */
		public void suppressLogging(Level level, Class<?>... loggers) {
			var suppressedLevel = toLogbackLevel(level);
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("Suppressing logs at or below {} for loggers {} on {}", level, Stream.of(loggers).map(Class::getName).toList(), System.identityHashCode(this));
			}
			// Put them all in one atomic operation
			overrides.putAll(Stream.of(loggers).collect(toMap(Class::getName, _->suppressedLevel)));
		}

		/**
		 * Like {@link #suppressLogging(Level, Class[])}, but identifies the loggers
		 * by their logger names instead of by their classes.
		 */
		public void suppressLogging(Level level, String... loggers) {
			var suppressedLevel = toLogbackLevel(level);
			overrides.putAll(Stream.of(loggers).collect(toMap(Function.identity(),_->suppressedLevel)));
		}

		private static ch.qos.logback.classic.Level toLogbackLevel(Level level) {
			return ch.qos.logback.classic.Level.toLevel(level.name());
		}
	}

	/**
	 * Causes the given <code>controller</code> to control logs emitted for any bosk
	 * configured to use the returned factory.
	 * <p>
	 * Technically, this returns a factory that, when {@link DriverFactory#build built},
	 * registers the given <code>controller</code> for logs having
	 * the MDC key {@link MdcKeys#BOSK_INSTANCE_ID} equal to the corresponding
	 * bosk's {@link Bosk#instanceID()}.
	 * (Registration cannot happen earlier, because the bosk's instance ID is not yet determined).
	 */
	public static <R extends StateTreeNode> DriverFactory<R> withController(LogController controller) {
		return (b, d) -> {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("Registering controller {} for bosk {} \"{}\"", System.identityHashCode(controller), b.instanceID(), b.name());
			}
			// We don't actually create a driver object here; we only
			// need to set the logging override at driver-creation time.
			LogController old = controllersByBoskID.put(b.instanceID().toString(), controller);
			assert old == null: "Must not create two log controllers for the same bosk. name:\"" + b.name() + "\" id:\"" + b.instanceID() + "\"";
			return d;
		};
	}

	@Override
	public FilterReply decide(Marker marker, Logger logger, ch.qos.logback.classic.Level messageLevel, String format, Object[] params, Throwable t) {
		if (logger.getLevel() != null) {
			// Respect user-supplied log levels.
			// Note that this only works for the exact logger, not a parent logger.
			// Ideally, any non-root parent logger with an explicit setting would override this,
			// but Logback sadly doesn't provide a way to check that.
			return NEUTRAL;
		}
		String boskID = MDC.get(BOSK_INSTANCE_ID);
		if (boskID == null) {
			return NEUTRAL;
		}
		var controller = controllersByBoskID.get(boskID);
		if (controller == null) {
			return NEUTRAL;
		}
		ch.qos.logback.classic.Level suppressedLevel = controller.overrides.get(logger.getName());
		if (suppressedLevel == null) {
			return NEUTRAL;
		}

		// There is an override. Deny if the message is no more severe than the override.
		if (suppressedLevel.isGreaterOrEqual(messageLevel)) {
			return DENY;
		} else {
			return NEUTRAL;
		}
	}

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(BoskLogFilter.class);
}
