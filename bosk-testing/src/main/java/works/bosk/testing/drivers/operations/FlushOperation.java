package works.bosk.testing.drivers.operations;

import java.io.IOException;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;

public record FlushOperation(
	BoskContext.Context boskContext
) implements DriverOperation {
	@Override
	public void submitTo(BoskDriver driver) throws IOException, InterruptedException {
		driver.flush();
	}
}
