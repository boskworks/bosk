package works.bosk.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * For an interface passed to <code>Bosk.buildReferences</code>,
 * this supplies the path string to be used to create the reference.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface ReferencePath {
	String value();
}
