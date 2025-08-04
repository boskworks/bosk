package works.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;
import works.bosk.testing.drivers.DriverConformanceTest;

public class BufferingDriverConformanceTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = BufferingDriver.factory();
	}

}
