package works.bosk.logback;

import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.event.Level;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.slf4j.event.Level.WARN;

/**
 * Suppresses expected log events for the duration of a test.
 * <p>
 * Intended for tests that deliberately provoke a warning or error:
 * without this annotation, the resulting event appears in the test output
 * and looks like a problem, even though the test asserts that it happened.
 * <p>
 * Suppression applies to events emitted by the thread running the test.
 * Events emitted by other threads are not affected;
 * see {@link SuppressLogsFilter} for the details.
 * Use {@link BoskLogFilter} instead for logs that a bosk emits from its own threads.
 * <p>
 * Can be applied to a class or a test method. Class-level annotations apply to every test
 * in the class, and method-level annotations add to them, overriding the class-level level
 * for the same logger. The annotation is repeatable, so a test can suppress several loggers
 * at different levels:
 * <pre>
 * &#64;SuppressLogs(value = ChattyThing.class, level = WARN)
 * &#64;SuppressLogs(value = VeryChattyThing.class, level = INFO)
 * class MyTest { ... }
 * </pre>
 * A level suppresses events at or below it: {@code WARN} leaves errors visible,
 * and {@code ERROR} suppresses everything, because nothing is more severe.
 *
 * @see SuppressLogsFilter
 * @see SuppressLogsExtension
 * @see works.bosk.logback
 */
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Inherited
@Repeatable(SuppressLogs.List.class)
@ExtendWith(SuppressLogsExtension.class)
public @interface SuppressLogs {
	/**
	 * The loggers to suppress.
	 */
	Class<?>[] value() default {};

	/**
	 * The most severe level to suppress.
	 */
	Level level() default WARN;

	@Retention(RUNTIME)
	@Target({TYPE, METHOD})
	@Inherited
	@interface List {
		SuppressLogs[] value();
	}
}
