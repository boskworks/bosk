package works.bosk.logback;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations;

/**
 * JUnit 5 extension that implements {@link SuppressLogs}.
 * <p>
 * Before each test, it combines the class-level and method-level annotations
 * into one suppression scope, with method-level levels taking precedence,
 * and opens that scope on the test's thread.
 * After each test, it closes the scope.
 * <p>
 * Opening the scope in {@code beforeEach} means the suppression lasts exactly as long as
 * the test method, on the same thread; this is what makes it safe under parallel execution.
 *
 * @see SuppressLogs
 * @see SuppressLogsFilter
 * @see works.bosk.logback
 */
public class SuppressLogsExtension implements BeforeEachCallback, AfterEachCallback {
	@Override
	public void beforeEach(ExtensionContext context) {
		if (context.getStore(NAMESPACE).get(SCOPE_KEY) != null) {
			// The extension can be registered more than once when @SuppressLogs is repeated.
			return;
		}
		Map<String, ch.qos.logback.classic.Level> overrides = new LinkedHashMap<>();
		for (SuppressLogs annotation : findRepeatableAnnotations(context.getRequiredTestClass(), SuppressLogs.class)) {
			addOverrides(overrides, annotation);
		}
		context.getTestMethod().ifPresent(method -> {
			for (SuppressLogs annotation : findRepeatableAnnotations(method, SuppressLogs.class)) {
				addOverrides(overrides, annotation);
			}
		});
		if (!overrides.isEmpty()) {
			context.getStore(NAMESPACE).put(SCOPE_KEY, SuppressLogsFilter.suppress(overrides));
		}
	}

	@Override
	public void afterEach(ExtensionContext context) {
		SuppressLogsFilter.Scope scope = context.getStore(NAMESPACE).remove(SCOPE_KEY, SuppressLogsFilter.Scope.class);
		if (scope != null) {
			scope.close();
		}
	}

	private static void addOverrides(Map<String, ch.qos.logback.classic.Level> overrides, SuppressLogs annotation) {
		if (annotation.value().length == 0) {
			throw new IllegalArgumentException("@SuppressLogs must name at least one logger");
		}
		ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.toLevel(annotation.level().name());
		for (Class<?> logger : annotation.value()) {
			overrides.put(logger.getName(), level);
		}
	}

	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SuppressLogsExtension.class);
	private static final String SCOPE_KEY = "scope";
}
