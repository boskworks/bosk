package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Indicates that a test class has one or more {@link Injected} fields.
 * Injectors can be configured using {@link InjectFrom}.
 * <p>
 * (We'd like {@link Injected} to add this automatically,
 * but JUnit 5 doesn't offer a way for an annotation that's not at the class level to add {@link ClassTemplate},
 * and that is required for parameter injection to produce multiple test instances for one class.)
 */
@ClassTemplate
@ExtendWith(FieldInjectionContextProvider.class)
@Retention(RUNTIME)
@Target(TYPE)
public @interface InjectFields {
}
