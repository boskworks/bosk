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
