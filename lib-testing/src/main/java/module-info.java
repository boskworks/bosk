/**
 * Internal test infrastructure for Bosk subprojects.
 */
module works.bosk.libtesting {
	requires transitive works.bosk.core;
	requires works.bosk.testing;
	requires transitive works.bosk.jackson;
	requires works.bosk.mongo;
	requires works.bosk.bosonSerializer;

	requires org.slf4j;
	requires org.junit.jupiter.api;
	requires org.junit.jupiter.params;


	exports works.bosk.libtesting;
}
