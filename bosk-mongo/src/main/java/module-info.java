/**
 * MongoDB-backed persistence {@link works.bosk.BoskDriver BoskDriver}.
 * <p>
 * Provides durable storage and replication via MongoDB.
 * The main entry point is {@link works.bosk.drivers.mongo.MongoDriver}, configured with
 * {@link works.bosk.drivers.mongo.MongoDriverSettings}. Operational state and health
 * information are exposed in {@link works.bosk.drivers.mongo.status}, and error
 * conditions are reported via {@link works.bosk.drivers.mongo.exceptions}.
 */
module works.bosk.mongo {
	requires com.fasterxml.jackson.annotation;
	requires transitive org.mongodb.bson;
	requires transitive org.mongodb.driver.core;
	requires org.mongodb.driver.sync.client;
	requires org.slf4j;
	requires transitive works.bosk.core;

	requires static lombok;

	exports works.bosk.drivers.mongo;
	exports works.bosk.drivers.mongo.exceptions;
	exports works.bosk.drivers.mongo.status;
}
