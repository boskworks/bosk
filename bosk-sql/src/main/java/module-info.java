module works.bosk.sql {
	requires com.fasterxml.jackson.databind;
	requires org.jooq;
	requires org.slf4j;
	requires works.bosk.core;
	requires works.bosk.jackson;

	exports works.bosk.drivers.sql;
}
