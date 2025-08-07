package works.bosk.drivers.mongo.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;
import works.bosk.testing.junit.Slow;

import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;

/**
 * Indicates that a test is going to use {@link MongoService},
 * and that it will use {@link MongoService#proxy()} to test
 * network outages and other errors.
 * Only one {@link DisruptsMongoProxy} test will run at a time.
 *
 * <p>
 * Each test class should use a distinct database name so that
 * classes can run in parallel.
 *
 * <p>
 * When in doubt, it's always safe to use this,
 * but doing so unnecessarily will impede parallelism.
 */
@Target({ ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock(value="mongoContainer", mode=READ_WRITE)
@Slow // These are inherently slow because they prevent tests from running in parallel
@Tag(DisruptsMongoProxy.TAG)
public @interface DisruptsMongoProxy {
	public static final String TAG = "disruptsMongoProxy";
}
