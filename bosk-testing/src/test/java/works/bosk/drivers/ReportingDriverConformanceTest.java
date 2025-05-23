package works.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;

class ReportingDriverConformanceTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = ReportingDriver.factory(_->{}, _->{});
	}

}
