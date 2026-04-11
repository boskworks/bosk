package works.bosk.logback;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static works.bosk.logback.ReplayLogsOnFailureExtension.UNSPECIFIED_CAPACITY;

/**
 * Configures log replay on failure for a test class.
 * <p>
 * When a test fails, any log events at or above the configured level are captured
 * and printed to the console, helping diagnose test failures.
 * <p>
 * <em>For a complete how-to guide, see the package documentation for {@link works.bosk.logback}.</em>
 *
 * @see ReplayLogsOnFailureExtension
 * @see RecordingTurboFilter
 * @see works.bosk.logback
 */
@Retention(RUNTIME)
@Target({TYPE,METHOD})
@Inherited
@ExtendWith(ReplayLogsOnFailureExtension.class)
public @interface ReplayLogsOnFailure {
	/**
	 * Whether log replay is enabled for this test class.
	 * Default is true.
	 */
	boolean enabled() default true;

	/**
	 * Maximum number of log events to buffer per test.
	 * When capacity is reached, oldest events are dropped.
	 */
	int capacity() default UNSPECIFIED_CAPACITY;
}
