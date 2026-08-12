package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a field or parameter on a test class/method for injection via {@link InjectFrom}.
 * <p>
 * The field/parameter will be set by the injection machinery before each test method
 * invocation, using values provided by the {@link Injector}s declared in {@code @InjectFrom}.
 *
 * @see InjectFrom
 */
@Retention(RUNTIME)
@Target({FIELD, PARAMETER})
public @interface Injected {
	/**
	 * Optional dimension name for this injection site. A site that specifies no
	 * dimension name uses the unnamed dimension, the dimension whose name is
	 * the empty string.
	 * For fields or parameters that use the same {@link Injector},
	 * if they have the same dimension, they receive the same value;
	 * if they have different dimensions, they receive the Cartesian product of value combinations.
	 */
	String value() default "";
}
