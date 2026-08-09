package works.bosk.drivers.mongo.internal;

import org.bson.conversions.Bson;

/**
 * Lets tests interpose on the driver's database writes. Each write goes through
 * {@link TransactionalCollection}, which passes the write's {@code filter} to the
 * interceptor before executing it. Throwing from {@link #beforeWrite} prevents the
 * write, so tests can simulate a failure partway through a multi-document operation;
 * the interceptor can also pause or observe writes.
 * <p>
 * Installed via {@link TestProbes#withWriteInterceptor(WriteInterceptor)}.
 */
@FunctionalInterface
public interface WriteInterceptor {
	/**
	 * @param filter the filter identifying the document(s) being written,
	 * or, for an {@code insertOne}, a filter matching the inserted document's {@code _id}
	 */
	void beforeWrite(Bson filter);

	static WriteInterceptor identity() {
		return filter -> {};
	}
}
