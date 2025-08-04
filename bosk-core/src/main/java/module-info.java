module works.bosk.core {
	requires org.jetbrains.annotations;
	requires org.objectweb.asm;
	requires org.pcollections;
	requires org.slf4j;
	requires works.bosk.annotations;

	requires static lombok;

	exports works.bosk;
	exports works.bosk.drivers;
	exports works.bosk.exceptions;
	exports works.bosk.bytecode to works.bosk.jackson;
	exports works.bosk.util to works.bosk.jackson, works.bosk.mongo;
	exports works.bosk.logging to works.bosk.logback, works.bosk.mongo, works.bosk.sql; // May be ok to export this, but give it a think first
}
