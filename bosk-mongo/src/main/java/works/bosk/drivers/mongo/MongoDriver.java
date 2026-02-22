package works.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import java.io.IOException;
import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.internal.FormatDriver;
import works.bosk.drivers.mongo.internal.MainDriver;
import works.bosk.drivers.mongo.status.MongoStatus;

/**
 * A {@link BoskDriver} that maintains the bosk state in a MongoDB database.
 * Multiple bosks, potentially in multiple separate processes,
 * can be configured to use the same database, thereby creating a replica set
 * with all bosks sharing the same state and receiving updates from each other.
 * <p>
 *
 * For convenience, if the database does not exist at the time of initialization,
 * this driver will create it and populate it with the state returned by calling
 * {@link BoskDriver#initialRoot} on the downstream driver.
 */
public sealed interface MongoDriver
	extends BoskDriver
	permits MainDriver, FormatDriver {

	/**
	 * Deserializes and re-serializes the entire bosk contents,
	 * thus updating the database to match the current serialized format.
	 *
	 * <p>
	 * Used to "upgrade" the database contents for schema evolution.
	 *
	 * <p>
	 * This method does not simply write the current in-memory bosk contents
	 * back into the database, because that would lead to race conditions
	 * with other update operations.
	 * Instead, in a causally-consistent transaction, it reads the current
	 * database state, deserializes it, re-serializes it, and writes it back.
	 * This produces predictable results even if done concurrently with
	 * other database updates.
	 *
	 * <p>
	 * This requires the database state to be in good condition at the outset;
	 * it can't generally be used to repair corrupted databases
	 * unless the corruption is so mild that it doesn't
	 * interfere with proper functioning beforehand.
	 * It can be expected to evolve the database from that of a supported prior format,
	 * but for unsupported formats or other corruption, YMMV.
	 */
	void refurbish() throws IOException;

	/**
	 * Requires a {@link Bosk.ReadSession}.
	 */
	MongoStatus readStatus() throws Exception;

	/**
	 * Frees up resources used by this driver and leaves it unusable.
	 *
	 * <p>
	 * This is done on a best-effort basis. It's more useful for tests than for production code,
	 * where there's usually no reason to close a driver.
	 */
	void close();

	static <RR extends StateTreeNode> MongoDriverFactory<RR> factory(
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonSerializer bsonSerializer
	) {
		driverSettings.validate();
		return (b, d) -> new MainDriver<>(b, clientSettings, driverSettings, bsonSerializer, d);
	}

	interface MongoDriverFactory<RR extends StateTreeNode> extends DriverFactory<RR> {
		@Override MongoDriver build(BoskInfo<RR> boskInfo, BoskDriver downstream);
	}

	/**
	 * A logger used to warn when we determine that the bosk collection is absent from the database
	 * and respond by automatically initializing it. In production, this is a noteworthy event that's
	 * expected to happen just once per database (so, essentially, once ever).
	 * <p>
	 * In tests, this is a very common occurrence, so the warnings would be counterproductive.
	 * We publicize the name of this logger so users can choose to set it to {@code ERROR}
	 * to suppress the warnings.
	 */
	String UNINITIALIZED_COLLECTION_LOGGER_NAME = MongoDriver.class.getName() + ".uninitializedCollection";
}
