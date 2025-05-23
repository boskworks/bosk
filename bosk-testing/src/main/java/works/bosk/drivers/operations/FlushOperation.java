package works.bosk.drivers.operations;

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
