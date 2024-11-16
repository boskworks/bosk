package works.bosk.junit;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Omits the test from the {@code smoke} task.
 */
@Target({METHOD, TYPE})
@Retention(RUNTIME)
@Tag("slow")
public @interface Slow {
}
