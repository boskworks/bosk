package works.bosk.jackson;

import java.util.stream.Stream;
import works.bosk.drivers.DriverConformanceTest;
import works.bosk.junit.ParametersByName;

import static works.bosk.AbstractRoundTripTest.jacksonRoundTripFactory;

public class JacksonRoundTripConformanceTest extends DriverConformanceTest {
	@ParametersByName
	JacksonRoundTripConformanceTest(JacksonSerializerConfiguration config) {
		driverFactory = jacksonRoundTripFactory(config);
	}

	@SuppressWarnings("unused")
	static Stream<JacksonSerializerConfiguration> config() {
		return Stream.of(JacksonSerializerConfiguration.defaultConfiguration());
	}
}
