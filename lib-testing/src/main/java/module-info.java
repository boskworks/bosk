/**
 * Internal test infrastructure for Bosk subprojects.
 */
module works.bosk.libtesting {
	requires works.bosk.core;
	requires works.bosk.testing;
	requires works.bosk.jackson;
	requires works.bosk.mongo;
	requires works.bosk.bosonSerializer;

	requires org.slf4j;
	requires org.junit.jupiter.api;
	requires org.junit.jupiter.params;

	requires static lombok;

	exports works.bosk.libtesting;

	provides org.junit.jupiter.api.extension.Extension with works.bosk.libtesting.TimeoutHintWatcher;
}
