package works.bosk.testing.drivers;

import org.junit.jupiter.api.BeforeEach;
import works.bosk.BoskConfig;

import static works.bosk.BoskConfig.simpleDriver;

/**
 * Makes sure {@link DriverConformanceTest} works properly by testing
 * {@link BoskConfig#simpleDriver} against itself.
 */
public class ConformanceMetaTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = simpleDriver();
	}

}
