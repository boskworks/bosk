package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Configures parameter injection for a test class by
 * specifying one or more {@link Injector}s
 * to be made available for injecting test method parameters
 * and class instance fields.
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
 * <p>
 * Injecting an {@code enum} class is equivalent to using a class like the one below.
 * <pre>{@code
 * public record MyEnumInjector() implements Injector {
 *     @Override
 *     public boolean supports(AnnotatedElement element, Class<?> elementType) {
 *         return elementType == MyEnum.class;
 *     }
 *
 *     @Override
 *     public List<?> values() { return Arrays.asList(MyEnum.values()); }
 * }
 * }</pre>
 *
 * If you want to customize the injection (say, by
 * injecting only a subset of the enum values),
 * then you can begin by replacing the enum class with an injector like this,
 * and then you can customize it.
 * <p>
 * As an alternative to declaring {@link Injector} classes, a test class can
 * declare {@link InjectorMethod @InjectorMethod} methods, each of which serves
 * as an inline {@link Injector} whose values are the returned
 * {@link java.util.stream.Stream}. All of these sources participate in the
 * same injection resolution.
 *
 * @see InjectedTest
 * @see Injected
 * @see InjectorMethod
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface InjectFrom {
	/**
	 * The injectors to use. They can be:
	 * <ul>
	 * <li>
	 *     An {@code enum}, to inject each of the enum's values, or
	 * </li>
	 * <li>
	 *     An {@link Injector}, to customize exactly what gets injected.
	 * </li>
	 * </ul>
	 */
	Class<?>[] value();
}
