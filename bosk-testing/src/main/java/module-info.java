/**
 * Testing utilities for Bosk applications.
 */
module works.bosk.testing {
	requires transitive works.bosk.core;
	requires transitive works.bosk.junit;
	requires transitive org.junit.jupiter.api;
	requires org.jspecify;
	requires org.slf4j;

	requires static lombok;

	exports works.bosk.testing;
	exports works.bosk.testing.drivers;
	exports works.bosk.testing.drivers.state;
	exports works.bosk.testing.drivers.operations;
	exports works.bosk.testing.junit;
}
