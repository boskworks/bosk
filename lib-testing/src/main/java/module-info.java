/**
 * Internal test infrastructure for Bosk subprojects.
 */
module works.bosk.libtesting {
	requires transitive works.bosk.core;
	requires transitive works.bosk.testing;
	requires transitive works.bosk.jackson;
	requires works.bosk.mongo;
	requires works.bosk.bosonSerializer;

	requires org.slf4j;
	requires org.junit.jupiter.api;
	requires org.junit.jupiter.params;

	requires static lombok;

	exports works.bosk.libtesting;
	exports works.bosk.libtesting.fuzz;
}
