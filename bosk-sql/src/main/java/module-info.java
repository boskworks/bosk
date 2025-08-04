module works.bosk.sql {
	requires transitive com.fasterxml.jackson.databind; // This really shouldn't be transitive
	requires transitive org.jooq;
	requires org.slf4j;
	requires transitive works.bosk.core;
	requires works.bosk.jackson;

	exports works.bosk.drivers.sql;
}
