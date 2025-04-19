package works.bosk.hello;

import org.springframework.stereotype.Component;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.BufferingDriver;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.hello.state.BoskState;
import works.bosk.hello.state.Target;
import works.bosk.logback.BoskLogFilter;
import works.bosk.logback.BoskLogFilter.LogController;
import works.bosk.opentelemetry.OpenTelemetryDriver;

@Component
public class HelloBosk extends Bosk<BoskState> {

	public HelloBosk(LogController logController) throws InvalidTypeException {
		super("Hello", BoskState.class, HelloBosk::defaultRoot, driverFactory(logController), Bosk.simpleRegistrar());
	}

	private static DriverFactory<BoskState> driverFactory(LogController logController) {
		return DriverStack.of(
			BoskLogFilter.withController(logController),
			OpenTelemetryDriver.wrapping(
				BufferingDriver.factory() // Defer operations to try to mix up the OTel context
			)
		);
	}

	public final Refs refs = buildReferences(Refs.class);

	public interface Refs {
		@ReferencePath("/targets") CatalogReference<Target> targets();
	}

	private static BoskState defaultRoot(Bosk<BoskState> bosk) {
		return new BoskState(
			Catalog.of(new Target(Identifier.from("world")))
		);
	}
}
