package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a test method that will have its parameters injected by {@link ParameterInjector}s.
 * <p>
 * If you're using the usual {@link org.junit.jupiter.api.TestInstance.Lifecycle#PER_METHOD PER_METHOD} lifecycle,
 * then the parameters can also be injected into the constructor.
 * No annotation on the constructor is needed to achieve this.
 * Conversely, if the constructor parameters are being injected,
 * then all test methods must use {@link InjectedTest}
 * even if they have no parameters themselves.
 * <p>
 * If you use {@link org.junit.jupiter.api.TestInstance.Lifecycle#PER_CLASS PER_CLASS},
 * and try to inject parameters into the constructor,
 * you'll get a somewhat confusing {@link ParameterResolutionException}.
 * <p>
 * <em>Note</em>: this doesn't currently work with {@code @ParameterizedTest}.
 * You don't get a cartesian product of the injected and parameterized values;
 * instead, you get a cartesian sum of contexts that are missing one or the other.
 * TODO: See if this is fixable.
 *
 * @see InjectFrom
 */
@Retention(RUNTIME)
@Target(METHOD)
@TestTemplate
@ExtendWith(ParameterInjectionContextProvider.class)
public @interface InjectedTest {
}
