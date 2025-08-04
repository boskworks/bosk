module works.bosk.mongo {
	requires com.fasterxml.jackson.annotation;
	requires org.mongodb.bson;
	requires org.mongodb.driver.core;
	requires org.slf4j;
	requires works.bosk.core;

	requires static lombok;

	exports works.bosk.drivers.mongo;
}
