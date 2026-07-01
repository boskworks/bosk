/**
 * Fundamental Bosk library providing enough to get started with no frills.
 * <p>
 * Start with {@link works.bosk the root package} for the primary Bosk APIs.
 * Additional packages provide driver interfaces ({@link works.bosk.drivers}),
 * common exceptions ({@link works.bosk.exceptions}), internal bytecode helpers
 * ({@link works.bosk.bytecode}), logging support ({@link works.bosk.logging}),
 * and reflection utilities ({@link works.bosk.util}).
 */
module works.bosk.core {
	requires org.jspecify;
	requires org.objectweb.asm;
	requires org.pcollections;
	requires org.slf4j;
	requires transitive works.bosk.annotations;

	requires static lombok;

	exports works.bosk;
	exports works.bosk.drivers;
	exports works.bosk.exceptions;

	// These selective exports are not here by design; more because we haven't
	// fully decided what parts of these packages should be a permanent part of
	// the core bosk API.
	exports works.bosk.bytecode to works.bosk.jackson;
	exports works.bosk.util to works.bosk.jackson, works.bosk.mongo, works.bosk.testing, works.bosk.libtesting;
	exports works.bosk.logging to works.bosk.logback, works.bosk.mongo, works.bosk.sql, works.bosk.testing; // May be ok to export this, but give it a think first
}
