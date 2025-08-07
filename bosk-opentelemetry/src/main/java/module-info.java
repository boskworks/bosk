module works.bosk.opentelemetry {
	requires io.opentelemetry.api;
	requires io.opentelemetry.context;
	requires transitive works.bosk.core;

	exports works.bosk.opentelemetry;
}
