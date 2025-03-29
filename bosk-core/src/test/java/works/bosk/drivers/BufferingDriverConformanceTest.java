package works.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;

public class BufferingDriverConformanceTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = BufferingDriver.factory();
	}

}
