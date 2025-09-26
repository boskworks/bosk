package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Configures parameter injection for a test class by
 * specifying one or more {@link ParameterInjector}s
 * to be made available for injecting parameters into test methods.
 * <p>
 * The order of the injectors is significant, for two reasons:
 * <ul>
 *     <li>
 *         Earlier injectors can provide parameters for the later injectors' constructors.
 *     </li>
 *     <li>
 *         When multiple injectors provide values for the same parameter,
 *         the later bundle overrides the earlier ones.
 *     </li>
 * </ul>
 *
 * For these purposes, inherited annotations are considered "earlier".
 * <p>
 * Injectors can, themselves, have constructor parameters injected by earlier injectors.
 * In that case, multiple instances of the same injector class will be created,
 * one for each distinct set of constructor parameters.
 *
 * @see InjectedTest
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface InjectFrom {
	Class<? extends ParameterInjector>[] value();
}
