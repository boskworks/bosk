package works.bosk.drivers.operations;

import java.io.IOException;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.MapValue;

public sealed interface DriverOperation permits UpdateOperation, FlushOperation {
	MapValue<String> diagnosticAttributes();

	/**
	 * Calls the appropriate <code>submit</code> method on the given driver.
	 * Any {@link BoskDiagnosticContext diagnostic context} is <em>not</em> propagated;
	 * if that behaviour is desired, the caller must do it.
	 */
	void submitTo(BoskDriver driver) throws IOException, InterruptedException;
}
