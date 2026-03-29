package works.bosk.drivers.mongo.internal;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * In SchemaEvolutionTest, marks the field that receives the "from" configuration.
 * <p>
 * Used with {@link To} to disambiguate between the two {@code Configuration}-valued
 * fields ({@code fromConfig} and {@code toConfig}), each fed by a separate injector
 * in the cartesian product.
 *
 * @see SchemaEvolutionTest#fromConfig
 * @see SchemaEvolutionTest.FromConfigInjector
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface From {
}
