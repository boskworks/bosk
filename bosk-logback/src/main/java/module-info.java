/**
 * Logback-specific logging utilities.
 * @see works.bosk.logback
 */
module works.bosk.logback {
	requires transitive ch.qos.logback.classic;
	requires transitive ch.qos.logback.core;
	requires transitive org.slf4j;
	requires transitive works.bosk.core;
	requires static org.junit.platform.commons;
	requires static org.junit.jupiter.api; // For replay

	exports works.bosk.logback;
}
