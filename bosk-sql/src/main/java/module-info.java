module works.bosk.sql {
	requires transitive tools.jackson.databind; // This really shouldn't be transitive
	requires transitive org.jooq;
	requires org.slf4j;
	requires transitive works.bosk.core;
	requires works.bosk.jackson;
	requires static transitive org.jspecify;

	exports works.bosk.drivers.sql;
}
