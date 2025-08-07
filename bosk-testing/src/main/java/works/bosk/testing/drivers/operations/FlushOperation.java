package works.bosk.testing.drivers.operations;

import java.io.IOException;
import works.bosk.BoskDriver;
import works.bosk.MapValue;

public record FlushOperation(
	MapValue<String> diagnosticAttributes
) implements DriverOperation {
	@Override
	public void submitTo(BoskDriver driver) throws IOException, InterruptedException {
		driver.flush();
	}
}
