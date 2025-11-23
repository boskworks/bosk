package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a test method that will have its parameters injected by {@link ParameterInjector}s.
 * <p>
 * There is no support for injecting parameters into the test class constructor.
 * Use {@code ParameterizedClass} for that.
 * <p>
 * There is also no support for using this alongside {@code @ParameterizedTest}
 * to mix and match parameter sources.
 * JUnit's parameterized tests do not do any kind of cartesian product calculation
 * to combine parameters.
 * If that's the functionality you're looking for, you want to use this annotation only.
 *
 * @see InjectFrom
 */
@Retention(RUNTIME)
@Target(METHOD)
@TestTemplate
@ExtendWith(ParameterInjectionContextProvider.class)
public @interface InjectedTest {
}
