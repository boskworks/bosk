package works.bosk.drivers.mongo.internal;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * In SchemaEvolutionTest, marks the field that receives the "to" configuration.
 * <p>
 * Used with {@link From} to disambiguate between the two {@code Configuration}-valued
 * fields ({@code fromConfig} and {@code toConfig}), each fed by a separate injector
 * in the cartesian product.
 *
 * @see SchemaEvolutionTest#toConfig
 * @see SchemaEvolutionTest.ToConfigInjector
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface To {
}
