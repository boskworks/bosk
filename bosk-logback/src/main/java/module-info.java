/**
 * Logback-specific logging utilities.
 * @see works.bosk.logback
 */
module works.bosk.logback {
	requires transitive ch.qos.logback.classic;
	requires transitive ch.qos.logback.core;
	requires transitive org.slf4j;
	requires transitive works.bosk.core;

	// We use requires static for JUnit dependencies so that people using
	// this for logback aren't forced also to include JUnit.
	// Presumably, everyone wanting this for JUnit will already have it as a dependency anyway.
	requires static org.junit.platform.commons;
	requires static org.junit.jupiter.api; // For replay

	exports works.bosk.logback;

	provides org.junit.jupiter.api.extension.Extension with works.bosk.logback.ReplayLogsOnFailureExtension;
}
