/**
 * Logback-specific logging utilities.
 */
module works.bosk.logback {
	requires transitive ch.qos.logback.classic;
	requires transitive ch.qos.logback.core;
	requires transitive org.slf4j;
	requires transitive works.bosk.core;

	exports works.bosk.logback;
}
