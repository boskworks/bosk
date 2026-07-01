package works.bosk.libtesting;

import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import static java.util.Objects.requireNonNull;

/**
 * Adds some more info that should help us quickly identify JUnit timeouts
 * in test runs.
 * Gradle has a way of making JUnit timeouts cryptic, so this can save some time
 * identifying and troubleshooting them.
 */
public class TimeoutHintWatcher implements TestWatcher {
	private final BiFunction<ExtensionContext, String, Optional<String>> configLookup;

	public TimeoutHintWatcher() {
		this(ExtensionContext::getConfigurationParameter);
	}

	TimeoutHintWatcher(BiFunction<ExtensionContext, String, Optional<String>> configLookup) {
		this.configLookup = requireNonNull(configLookup);
	}

	@Override
	public void testFailed(@Nullable ExtensionContext context, Throwable cause) {
		TimeoutException timeout = findJUnitTimeout(cause);
		if (timeout != null) {
			String duration = configLookup.apply(context, "junit.jupiter.execution.timeout.default")
				.map(v -> "(configured for " + v + ")")
				.orElse("");
			System.err.println("\n=== JUnit timeout " + duration + " ===");
			timeout.printStackTrace(System.err);
		}
	}

	private static TimeoutException findJUnitTimeout(Throwable t) {
		Throwable current = t;
		while (current != null) {
			if (current instanceof TimeoutException te && isJUnitTimeout(te)) {
				return te;
			}
			for (Throwable suppressed : current.getSuppressed()) {
				TimeoutException found = findJUnitTimeout(suppressed);
				if (found != null) {
					return found;
				}
			}
			current = current.getCause();
		}
		return null;
	}

	private static boolean isJUnitTimeout(TimeoutException e) {
		StackTraceElement[] frames = e.getStackTrace();
		return frames.length > 0
			&& frames[0].getClassName().startsWith("org.junit");
	}
}
