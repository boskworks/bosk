package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a field on a test class for class-level injection via {@link InjectFrom}.
 * <p>
 * The field will be set by {@link FieldInjectionContextProvider}
 * before each test method invocation, using values provided by the
 * {@link Injector}s declared in {@code @InjectFrom}.
 *
 * @see InjectFrom
 * @see FieldInjectionContextProvider
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface Injected {
}
