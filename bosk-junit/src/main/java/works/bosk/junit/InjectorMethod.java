package works.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a static method that supplies values for injection, as an alternative
 * to declaring an {@link Injector} class in {@link InjectFrom @InjectFrom}.
 * <p>
 * An {@code @InjectorMethod} is an injector expressed as a method: the method's
 * return value provides the injector's {@link Injector#values values}, and the
 * method's parameters are resolved like an injector's constructor arguments.
 * It serves as a source for both {@link Injected @Injected} fields and
 * {@link InjectedTest @InjectedTest} method parameters, on any
 * {@linkplain Injected dimension name}.
 * <p>
 * The annotated method must be {@code static} and must return a {@link java.util.stream.Stream}
 * of a concrete element type {@code T}; a single value is expressed as
 * {@code Stream.of(value)}. The element type {@code T} is the type that this
 * source serves: a field or parameter is injected from this method only if its
 * declared type is {@code T}. (Matching is by type only, like {@linkplain InjectFrom
 * enum injection}; {@link Injector} classes remain the way to customize the
 * matching logic.) By default the element type is the reference type; set
 * {@link #primitive()} to serve the corresponding primitive-typed injection
 * sites instead.
 * <p>
 * The method's own parameters are injected from sources that precede it: any
 * {@link InjectFrom} source, and any {@code @InjectorMethod} declared in a
 * superclass. A method cannot be fed by another {@code @InjectorMethod} in the
 * same class, since the order of same-class methods is undefined.
 *
 * @see InjectFrom
 * @see Injector
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface InjectorMethod {
	/**
	 * If {@code true}, this source serves the primitive counterpart of its
	 * element type instead of the element type itself: {@code Stream<Boolean>}
	 * with {@code primitive = true} serves {@code boolean}-typed injection
	 * sites. The element type must be a boxed primitive, enforced at discovery
	 * time; the values remain the boxed type and are unboxed on injection.
	 */
	boolean primitive() default false;
}
