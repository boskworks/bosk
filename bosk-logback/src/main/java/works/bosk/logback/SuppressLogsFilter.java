package works.bosk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Marker;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static java.util.stream.Collectors.toMap;

/**
 * A Logback {@link TurboFilter} that suppresses expected log events
 * emitted by the thread that opened the scope.
 * <p>
 * Intended for tests that deliberately provoke a warning or error:
 * the test can suppress the resulting event so it does not look like a problem
 * in the test output.
 * <p>
 * Suppression applies only to the thread that opened the scope,
 * so tests running in parallel do not interfere with each other.
 * Events emitted by other threads, including threads started by the code under test,
 * are not suppressed. For logs that a {@link works.bosk.Bosk} emits from its own threads,
 * use {@link BoskLogFilter} instead, because those events carry the bosk's MDC instance ID.
 * <p>
 * Scopes nest: the most recently opened scope that names a logger decides its fate,
 * and an outer scope applies again once the inner one is closed.
 * <p>
 * Add this filter to your logback-test.xml, alongside the other bosk filters:
 * <pre>
 * &lt;turboFilter class="works.bosk.logback.SuppressLogsFilter"/&gt;
 * </pre>
 *
 * @see SuppressLogs
 * @see works.bosk.logback
 */
public final class SuppressLogsFilter extends TurboFilter {
	private static final ThreadLocal<Deque<Map<String, Level>>> scopesByThread =
		ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * Suppresses events from the given loggers at or below {@code level}
	 * on the current thread until the returned scope is closed.
	 *
	 * @param level   the most severe level to suppress
	 * @param loggers the loggers whose events are suppressed at that level and below
	 */
	public static Scope suppressForCurrentThread(org.slf4j.event.Level level, Class<?>... loggers) {
		return suppress(overrides(level, Stream.of(loggers).map(Class::getName).toList()));
	}

	/**
	 * Suppresses the given loggers on the current thread until the returned scope is closed.
	 * This is the form used by {@link SuppressLogsExtension}, which combines
	 * several annotations into one scope.
	 */
	static Scope suppress(Map<String, Level> overrides) {
		Deque<Map<String, Level>> scopes = scopesByThread.get();
		scopes.push(Map.copyOf(overrides));
		return () -> {
			scopes.pop();
			if (scopes.isEmpty()) {
				scopesByThread.remove();
			}
		};
	}

	@Override
	public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, @Nullable Throwable t) {
		for (Map<String, Level> scope : scopesByThread.get()) {
			Level suppressedLevel = scope.get(logger.getName());
			if (suppressedLevel != null) {
				return suppressedLevel.isGreaterOrEqual(level) ? DENY : NEUTRAL;
			}
		}
		return NEUTRAL;
	}

	/**
	 * A suppression that lasts until it is closed.
	 * Unlike {@link AutoCloseable}, {@link #close()} throws no checked exceptions,
	 * so it can be used directly in try-with-resources blocks.
	 */
	public interface Scope extends AutoCloseable {
		@Override
		void close();
	}

	private static Map<String, Level> overrides(org.slf4j.event.Level level, List<String> loggerNames) {
		Level suppressedLevel = Level.toLevel(level.name());
		return loggerNames.stream().collect(toMap(name -> name, _ -> suppressedLevel));
	}

}
