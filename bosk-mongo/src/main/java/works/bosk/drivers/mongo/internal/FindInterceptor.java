package works.bosk.drivers.mongo.internal;

import org.bson.conversions.Bson;

/**
 * Lets tests interpose on the driver's database reads. Each read goes through
 * {@link TransactionalCollection.FindBuilder#cursor()}, which passes the read's
 * {@code filter} and {@link ReadOptions} and the underlying {@link DocCursor}
 * to the interceptor, which may return a wrapped cursor that pauses, alters,
 * or truncates the read.
 * <p>
 * Installed via {@link TestProbes#withFindInterceptor(FindInterceptor)}.
 */
@FunctionalInterface
public interface FindInterceptor {
	DocCursor intercept(Bson filter, ReadOptions options, DocCursor cursor);

	static FindInterceptor identity() {
		return (filter, options, cursor) -> cursor;
	}
}
