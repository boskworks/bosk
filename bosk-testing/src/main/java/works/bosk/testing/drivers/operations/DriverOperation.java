package works.bosk.testing.drivers.operations;

import java.io.IOException;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;

public sealed interface DriverOperation permits UpdateOperation, FlushOperation {
	BoskContext.Context boskContext();

	/**
	 * Calls the appropriate <code>submit</code> method on the given driver.
	 * Any {@link BoskContext bosk context} is <em>not</em> propagated;
	 * if that behaviour is desired, the caller must do it.
	 */
	void submitTo(BoskDriver driver) throws IOException, InterruptedException;
}
