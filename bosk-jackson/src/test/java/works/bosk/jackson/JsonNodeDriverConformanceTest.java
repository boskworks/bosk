package works.bosk.jackson;

import org.junit.jupiter.api.BeforeEach;
import works.bosk.testing.drivers.DriverConformanceTest;

// TODO: This currently doesn't test much beyond the driver's ability to pass updates downstream
class JsonNodeDriverConformanceTest extends DriverConformanceTest {
	@BeforeEach
	void setUp() {
		driverFactory = JsonNodeDriver.factory(new JacksonSerializer());
	}
}
