package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a test method that will have its parameters injected by {@link Injector}s
 * declared in {@link InjectFrom @InjectFrom}.
 * <p>
 * Use this annotation on test methods that need method-level parameter injection.
 * For class-level field injection, use {@link Injected @Injected} on fields instead.
 * <p>
 * There is also no support for using this alongside {@code @ParameterizedTest}
 * to mix and match parameter sources.
 * JUnit's parameterized tests do not do any kind of cartesian product calculation
 * to combine parameters.
 * If that's the functionality you're looking for, you want to use this annotation only.
 *
 * @see InjectFrom
 * @see Injected
 */
@Retention(RUNTIME)
@Target(METHOD)
@TestTemplate
@ExtendWith(ParameterInjectionContextProvider.class)
public @interface InjectedTest {
}
