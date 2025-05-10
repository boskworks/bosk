package works.bosk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import works.bosk.Bosk;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.logging.MdcKeys;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static java.util.stream.Collectors.toMap;
import static works.bosk.logging.MdcKeys.BOSK_INSTANCE_ID;

public class BoskLogFilter extends Filter<ILoggingEvent> {
	private static final ConcurrentHashMap<String, LogController> controllersByBoskID = new ConcurrentHashMap<>();

	public static final class LogController {
		final Map<String, Level> overrides = new ConcurrentHashMap<>();

		// We'd like to use SLF4J's "Level" but that doesn't support OFF
		public void setLogging(Level level, Class<?>... loggers) {
			// Put them all in one atomic operation
			overrides.putAll(Stream.of(loggers).collect(toMap(Class::getName, c->level)));
		}
	}

	public static <R extends StateTreeNode> DriverFactory<R> withOverrides(Level level, Class<?>... loggers) {
		LogController controller = new LogController();
		controller.setLogging(level, loggers);
		return withController(controller);
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
				LOGGER.debug("Registering controller {} for bosk {} \"{}\"", System.identityHashCode(controller), b.instanceID(), b.name(), new Exception("Stack trace"));
			}
			// We don't actually create a driver object here; we only
			// need to set the logging override at driver-creation time.
			LogController old = controllersByBoskID.put(b.instanceID().toString(), controller);
			assert old == null: "Must not create two log controllers for the same bosk. name:\"" + b.name() + "\" id:\"" + b.instanceID() + "\"";
			return d;
		};
	}

	@Override
	public FilterReply decide(ILoggingEvent event) {
		String boskID = MDC.get(BOSK_INSTANCE_ID);
		if (boskID == null) {
			return NEUTRAL;
		}
		var controller = controllersByBoskID.get(boskID);
		if (controller == null) {
			return NEUTRAL;
		}
		Level level = controller.overrides.get(event.getLoggerName());
		if (level == null) {
			return NEUTRAL;
		}
		if (event.getLevel().isGreaterOrEqual(level)) {
			return NEUTRAL;
		} else {
			return DENY;
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BoskLogFilter.class);
}
