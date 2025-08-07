package works.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;
import works.bosk.testing.drivers.DriverConformanceTest;

import static works.bosk.AbstractRoundTripTest.bsonRoundTripFactory;

/**
 * TODO: Move to bosk-mongo
 */
public class BsonRoundTripConformanceTest extends DriverConformanceTest {
	@BeforeEach
	void setupDriverFactory() {
		driverFactory = bsonRoundTripFactory();
	}
}
